"""Integrity and release-gate tests; all media and catalogs live in temp dirs.

The tiny GLB/WAV fixtures below are synthetic validation inputs. They are never
production assets and are never copied into the application's real asset tree.
Run: python3 -m unittest discover -s tests -p 'test_assets.py' -v
"""

import argparse
import copy
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import wave


PROJECT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"
PACKAGE_NAME = "com.example.varcotest"


def make_glb(path, document=None):
    document = document or {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{}]}
    body = json.dumps(document).encode("utf-8")
    body += b" " * (-len(body) % 4)
    path.write_bytes(struct.pack("<4sII", b"glTF", 2, 20 + len(body))
                     + struct.pack("<II", len(body), 0x4E4F534A) + body)
    return path


def make_wav(path, frames=441):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(44100)
        output.writeframes(b"\0\0" * frames)
    return path


def catalog_fixture():
    return {
        "version": 1, "appName": "TEST FIXTURE", "releaseReady": False,
        "scenes": [
            {
                "id": scene, "title": "시험 장면", "available": False,
                "modelUrl": None, "attribution": "PENDING",
                "layers": [
                    {"id": "layer-{}".format(i), "label": "음원 {}".format(i),
                     "available": False, "file": None, "volume": 0.6 - i * 0.1}
                    for i in range(3)
                ],
            } for scene in ("rain", "camp", "space")
        ],
        "products": {
            "sound": {"url": "https://sound.varco.ai/ko?utm_source=test"},
            "threeD": {"url": "https://3d.varco.ai/ko?utm_source=test"},
            "api": {"url": "https://api.varco.ai/ko?utm_source=test"},
        },
    }


class AssetTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="varco-asset-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        for filename in ("import-assets.py", "preflight.py"):
            shutil.copyfile(PROJECT / "scripts" / filename, self.scripts / filename)
        self.assets = self.root / "app/src/main/assets"
        (self.assets / "web").mkdir(parents=True)
        (self.assets / "web/index.html").write_text("<!doctype html><title>Fixture</title>")
        self.catalog_path = self.assets / "catalog.json"
        self.write_catalog(catalog_fixture())
        self.manifest = self.root / "app/src/main/AndroidManifest.xml"
        self.manifest.write_text('<manifest xmlns:android="{}" package="{}"><application /></manifest>'.format(ANDROID_NS, PACKAGE_NAME))
        self.model = make_glb(self.root / "synthetic-test-only.glb")
        self.audio = make_wav(self.root / "synthetic-test-only.wav")
        spec = importlib.util.spec_from_file_location("fixture_varco_import", self.scripts / "import-assets.py")
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def read_catalog(self):
        return json.loads(self.catalog_path.read_text())

    def write_catalog(self, catalog):
        self.catalog_path.write_text(json.dumps(catalog, ensure_ascii=False))

    def args(self, **changes):
        values = dict(scene="rain", model=str(self.model), audio=[str(self.audio)],
                      source_note="TEST ONLY: synthetic validation fixtures, not production VARCO output",
                      rights_confirmed=True)
        values.update(changes)
        return argparse.Namespace(**values)

    def run_preflight(self, release=False, release_candidate=False):
        command = [sys.executable, str(self.scripts / "preflight.py")]
        if release:
            command.append("--release")
        if release_candidate:
            command.append("--release-candidate")
        return subprocess.run(command, capture_output=True, text=True, timeout=15)

    def write_release_evidence(self):
        (self.root / "docs").mkdir(exist_ok=True)
        evidence = {key: True for key in (
            "assetReviewCompleted", "emulatorQaCompleted", "realDeviceQaCompleted",
            "iarcEvidence", "approvedPackageName", "supportInboxConfirmed", "webviewCategoryConfirmed")}
        evidence.update(approvedPackageName=PACKAGE_NAME,
                        privacyUrl="https://example.com/privacy", supportUrl="https://example.com/support")
        (self.root / "docs/release-evidence.json").write_text(json.dumps(evidence))

    def test_valid_embedded_glb_and_positive_pcm_wav_metadata(self):
        glb = self.module.inspect_glb(self.model)
        wav = self.module.inspect_wav(self.audio)
        self.assertEqual(glb["glbVersion"], 2)
        self.assertEqual(len(glb["sha256"]), 64)
        self.assertEqual(wav["sampleRateHz"], 44100)
        self.assertEqual(wav["channels"], 1)
        self.assertAlmostEqual(wav["durationSeconds"], 0.01)

    def test_glb_rejects_bad_magic_version_size_and_truncated_json(self):
        good = self.model.read_bytes()
        mutations = (
            b"bad", b"NOPE" + good[4:],
            good[:4] + struct.pack("<I", 1) + good[8:],
            good[:8] + struct.pack("<I", len(good) + 4) + good[12:],
            good[:16] + struct.pack("<I", 0x004E4942) + good[20:],
            good[:-4],
        )
        for invalid in mutations:
            with self.subTest(length=len(invalid)):
                self.model.write_bytes(invalid)
                with self.assertRaises(ValueError):
                    self.module.inspect_glb(self.model)

    def test_glb_rejects_external_buffer_and_image_but_accepts_data_uri(self):
        for collection, uri in (("buffers", "mesh.bin"), ("images", "https://example.com/private.png")):
            with self.subTest(collection=collection):
                make_glb(self.model, {"asset": {"version": "2.0"}, collection: [{"uri": uri}]})
                with self.assertRaisesRegex(ValueError, "external resources"):
                    self.module.inspect_glb(self.model)
        make_glb(self.model, {"asset": {"version": "2.0"}, "images": [{"uri": "data:image/png;base64,AAAA"}]})
        self.assertEqual(self.module.inspect_glb(self.model)["format"], "glb")

    def test_glb_rejects_declared_compression_that_needs_unbundled_decoders(self):
        for field in ("extensionsUsed", "extensionsRequired"):
            for extension in ("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu"):
                with self.subTest(field=field, extension=extension):
                    make_glb(self.model, {"asset": {"version": "2.0"}, field: [extension]})
                    with self.assertRaisesRegex(ValueError, "unavailable decoders"):
                        self.module.inspect_glb(self.model)

    def test_glb_rejects_malformed_extension_declarations(self):
        for declaration in ("KHR_draco_mesh_compression", [None], {}):
            with self.subTest(declaration=declaration):
                make_glb(self.model, {"asset": {"version": "2.0"}, "extensionsUsed": declaration})
                with self.assertRaisesRegex(ValueError, "array of extension names"):
                    self.module.inspect_glb(self.model)

    def test_wav_rejects_invalid_header_zero_duration_and_truncated_data(self):
        self.audio.write_bytes(b"not a WAV file")
        with self.assertRaises((wave.Error, EOFError)):
            self.module.inspect_wav(self.audio)
        make_wav(self.audio, frames=0)
        with self.assertRaisesRegex(ValueError, "positive duration"):
            self.module.inspect_wav(self.audio)
        make_wav(self.audio)
        self.audio.write_bytes(self.audio.read_bytes()[:-2])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.module.inspect_wav(self.audio)

    def test_missing_catalog_is_read_only(self):
        self.catalog_path.unlink()
        before = set(self.root.rglob("*"))
        with self.assertRaisesRegex(ValueError, "Catalog is not present"):
            self.module.import_assets(self.args())
        self.assertEqual(before, set(self.root.rglob("*")))

    def test_import_requires_rights_note_and_at_most_three_wavs(self):
        before = self.catalog_path.read_bytes()
        for changes in ({"rights_confirmed": False},
                        {"rights_confirmed": True, "development_only": True}, {"source_note": "  "},
                        {"audio": []}, {"audio": [str(self.audio)] * 4}):
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    self.module.import_assets(self.args(**changes))
                self.assertEqual(before, self.catalog_path.read_bytes())
        self.assertFalse((self.assets / "content").exists())

    def test_cli_requires_exactly_one_explicit_import_mode(self):
        command = [sys.executable, str(self.scripts / "import-assets.py"),
                   "--scene", "rain", "--model", str(self.model), "--audio", str(self.audio),
                   "--source-note", "TEST ONLY: temporary synthetic fixture"]
        before = {str(p.relative_to(self.root)): p.read_bytes()
                  for p in self.root.rglob("*") if p.is_file()}
        for flags in ([], ["--rights-confirmed", "--development-only"]):
            with self.subTest(flags=flags):
                result = subprocess.run(command + flags, capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("--rights-confirmed", result.stderr)
                self.assertIn("--development-only", result.stderr)
                self.assertEqual(before, {str(p.relative_to(self.root)): p.read_bytes()
                                         for p in self.root.rglob("*") if p.is_file()})

    def test_development_cli_import_is_usable_but_release_stays_blocked(self):
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.write_release_evidence()
        result = subprocess.run([
            sys.executable, str(self.scripts / "import-assets.py"), "--scene", "rain",
            "--model", str(self.model), "--audio", str(self.audio),
            "--source-note", "TEST ONLY: synthetic development input", "--development-only",
        ], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("rights remain unverified", result.stdout)
        self.assertNotIn("operator attestation", result.stdout)
        catalog = self.read_catalog()
        scene = catalog["scenes"][0]
        self.assertTrue(scene["available"])
        self.assertTrue(scene["layers"][0]["available"])
        self.assertFalse(catalog["releaseReady"])
        self.assertEqual(scene["assetStatus"], "imported-unverified")
        self.assertEqual((self.assets / scene["modelUrl"].lstrip("/")).read_bytes(), self.model.read_bytes())
        self.assertEqual((self.assets / scene["layers"][0]["file"]).read_bytes(), self.audio.read_bytes())
        self.assertTrue(self.module.scene_files_ready(scene))
        self.assertTrue(self.module.scene_development_ready(scene))
        self.assertFalse(self.module.scene_ready(scene))
        for record in self.module.scene_provenance_records(scene):
            self.assertIs(record["rightsConfirmed"], False)
            self.assertNotIn("rightsConfirmedAt", record)
            self.assertEqual(record["provenanceType"], "development-import-unverified-rights")
        before_preflight = self.catalog_path.read_bytes()
        debug = self.run_preflight()
        release = self.run_preflight(release=True)
        self.assertEqual(debug.returncode, 0, debug.stdout + debug.stderr)
        self.assertNotEqual(release.returncode, 0)
        self.assertIn("Unverified or missing genuine VARCO assets: rain", release.stdout)
        self.assertNotIn("Release evidence missing", release.stdout)
        self.assertEqual(before_preflight, self.catalog_path.read_bytes())

    def test_individual_sound_confirmation_allows_debug_but_cannot_promote_release(self):
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.write_release_evidence()
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        catalog = self.read_catalog()
        scene = catalog["scenes"][0]
        sound = scene["layers"][0]["provenance"]
        sound.update(rightsConfirmed=True, rightsConfirmedAt="2026-10-17",
                     provenanceType="operator-attestation")
        self.write_catalog(catalog)
        before = self.catalog_path.read_bytes()
        self.assertTrue(self.module.scene_development_ready(scene))
        self.assertFalse(self.module.scene_ready(scene))
        self.assertEqual(self.run_preflight().returncode, 0)
        self.assertEqual(self.run_preflight(release_candidate=True).returncode, 0)
        release = self.run_preflight(release=True)
        self.assertNotEqual(release.returncode, 0)
        self.assertIn("Unverified or missing genuine VARCO assets: rain", release.stdout)
        self.assertEqual(before, self.catalog_path.read_bytes())
        self.assertFalse(scene["modelProvenance"]["rightsConfirmed"])
        self.assertFalse(scene["provenance"]["rightsConfirmed"])
        for patch in ({"rightsConfirmedAt": ""}, {"rightsConfirmedAt": None},
                      {"provenanceType": "development-import-unverified-rights"}):
            with self.subTest(patch=patch):
                invalid = copy.deepcopy(catalog)
                invalid["scenes"][0]["layers"][0]["provenance"].update(patch)
                self.write_catalog(invalid)
                self.assertNotEqual(self.run_preflight().returncode, 0)
        self.write_catalog(catalog)
        path = self.assets / scene["layers"][0]["file"]
        path.write_bytes(b"corrupt confirmed sound")
        self.assertNotEqual(self.run_preflight().returncode, 0)

    def test_release_candidate_keeps_unverified_rights_and_submission_evidence_unchanged(self):
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        evidence_path = self.root / "docs/release-evidence.json"
        evidence_path.parent.mkdir()
        evidence_path.write_text(json.dumps({"assetReviewCompleted": False, "emulatorQaCompleted": False,
            "supportInboxConfirmed": False, "iarcEvidence": None, "privacyUrl": None}))
        before = (self.catalog_path.read_bytes(), evidence_path.read_bytes())
        candidate = self.run_preflight(release_candidate=True)
        self.assertEqual(candidate.returncode, 0, candidate.stdout + candidate.stderr)
        self.assertIn("Release-candidate technical preflight passed", candidate.stdout)
        self.assertIn("PENDING: Release evidence missing: iarcEvidence", candidate.stdout)
        self.assertNotEqual(self.run_preflight(release=True).returncode, 0)
        self.assertEqual(before, (self.catalog_path.read_bytes(), evidence_path.read_bytes()))
        self.assertEqual(self.run_preflight(release=True, release_candidate=True).returncode, 2)

    def test_release_candidate_rejects_missing_assets_corruption_and_unexpected_permissions(self):
        missing = self.run_preflight(release_candidate=True)
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("Release candidate requires complete packaged assets", missing.stdout)
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        scene = self.read_catalog()["scenes"][0]
        model = self.assets / scene["modelUrl"].lstrip("/")
        original = model.read_bytes()
        model.write_bytes(b"changed after import")
        self.assertNotEqual(self.run_preflight(release_candidate=True).returncode, 0)
        model.write_bytes(original)
        self.manifest.write_text('<manifest xmlns:android="{}" package="{}">'
            '<uses-permission android:name="android.permission.INTERNET" /><application /></manifest>'
            .format(ANDROID_NS, PACKAGE_NAME))
        invalid = self.run_preflight(release_candidate=True)
        self.assertNotEqual(invalid.returncode, 0)
        self.assertIn("must not introduce permissions", invalid.stdout)

    def test_candidate_builder_requires_external_signing_and_generates_nondebug_manifest(self):
        # Stop at the first SDK command: no compilation, key generation or APK signing.
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        (self.root / "app/build.gradle.kts").write_text('android {\n applicationId = "' + PACKAGE_NAME + '"\n versionCode = 7\n versionName = "2.3.4"\n}\n')
        builder = self.scripts / "build-apk.sh"
        shutil.copyfile(PROJECT / "scripts/build-apk.sh", builder)
        tools = self.root / "fake-build-tools"
        tools.mkdir()
        for name in ("aapt2", "d8", "zipalign", "apksigner"):
            path = tools / name
            path.write_text("#!/bin/sh\nexit 73\n")
            path.chmod(0o700)
        env = os.environ.copy()
        for key in ("VARCO_KEYSTORE", "VARCO_KEY_ALIAS", "VARCO_STORE_PASSWORD", "VARCO_KEY_PASSWORD"):
            env.pop(key, None)
        env.update(VARCO_BUILD_TOOLS=str(tools), ANDROID_SDK_ROOT=str(self.root / "fake-sdk"))
        work = self.root / ".build/release-candidate"
        stale = [work / "classes/ai/nc/varcospaces/MainActivity$3.class", work / "dex/classes2.dex"]
        preserved = [self.root / ".build/debug/classes/Keep.class", self.root / ".build/release/dex/classes.dex",
                     self.root / ".build/signing/keep-marker", work / "keep-marker",
                     self.root / "dist/existing-release.apk", self.model, self.catalog_path]
        for path in stale + preserved[:-2]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"test marker, not a compiled class, APK or signing key")
        before = {path: path.read_bytes() for path in preserved}
        command = ["bash", str(builder), "release-candidate"]
        missing = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("VARCO_KEYSTORE", missing.stderr)
        self.assertTrue(all(path.exists() for path in stale), "Missing signing configuration must stop before cleaning")
        env.update(VARCO_KEYSTORE="/unused-test-key.p12", VARCO_KEY_ALIAS="fixture",
                   VARCO_STORE_PASSWORD="unused-test-value", VARCO_KEY_PASSWORD="unused-test-value")
        candidate = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(candidate.returncode, 73, candidate.stdout + candidate.stderr)
        import xml.etree.ElementTree as ET
        generated = ET.parse(self.root / ".build/release-candidate/AndroidManifest.xml").getroot()
        self.assertEqual(generated.find("application").get("{" + ANDROID_NS + "}debuggable"), "false")
        self.assertEqual(generated.get("{" + ANDROID_NS + "}versionCode"), "7")
        self.assertEqual(generated.get("{" + ANDROID_NS + "}versionName"), "2.3.4")
        self.assertEqual((work / "version-name.txt").read_text(), "2.3.4")
        self.assertEqual(list((work / "classes").iterdir()), [], "Old inner classes must not reach the next JAR")
        self.assertEqual(list((work / "dex").iterdir()), [], "Old DEX files must not reach the next APK")
        self.assertEqual(before, {path: path.read_bytes() for path in preserved})
        self.assertFalse((self.root / ".build/debug.keystore").exists())
        self.assertEqual(list((self.root / "dist").iterdir()), [self.root / "dist/existing-release.apk"])

    def test_development_import_revokes_old_attestation_and_disables_unused_layers(self):
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args(audio=[str(self.audio)] * 3))
        self.assertTrue(self.read_catalog()["releaseReady"])
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        catalog = self.read_catalog()
        scene = catalog["scenes"][0]
        self.assertFalse(catalog["releaseReady"])
        for record in self.module.scene_provenance_records(scene):
            self.assertIs(record["rightsConfirmed"], False)
            self.assertNotIn("rightsConfirmedAt", record)
            self.assertEqual(record["provenanceType"], "development-import-unverified-rights")
        for layer in scene["layers"][1:]:
            self.assertFalse(layer["available"])
            self.assertIsNone(layer["file"])
            self.assertNotIn("provenance", layer)
        # Re-checking or importing again without approval must not promote it.
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        self.assertFalse(self.read_catalog()["releaseReady"])
        # An explicit later rights confirmation remains the only supported promotion.
        self.module.import_assets(self.args())
        self.assertTrue(self.read_catalog()["releaseReady"])
        for record in self.module.scene_provenance_records(self.read_catalog()["scenes"][0]):
            self.assertIs(record["rightsConfirmed"], True)
            self.assertIn("rightsConfirmedAt", record)
            self.assertEqual(record["provenanceType"], "operator-attestation")

    def test_development_debug_exception_requires_explicit_consistent_provenance(self):
        self.module.import_assets(self.args(rights_confirmed=False, development_only=True))
        original = self.read_catalog()
        variants = []
        for field in ("provenance", "modelProvenance"):
            changed = copy.deepcopy(original)
            changed["scenes"][0][field]["provenanceType"] = "operator-attestation"
            variants.append(changed)
        changed = copy.deepcopy(original)
        changed["scenes"][0]["layers"][0]["provenance"]["rightsConfirmedAt"] = "stale approval"
        variants.append(changed)
        changed = copy.deepcopy(original)
        changed["releaseReady"] = True
        variants.append(changed)
        for changed in variants:
            with self.subTest(catalog=changed):
                self.write_catalog(changed)
                result = self.run_preflight()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Scene marked available", result.stdout)
        self.write_catalog(original)
        path = self.assets / original["scenes"][0]["layers"][0]["file"]
        path.write_bytes(b"corrupted after development import")
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Scene marked available", result.stdout)

    def test_import_preserves_ui_and_products_disables_unused_layers(self):
        original = self.read_catalog()
        self.assertEqual(self.module.import_assets(self.args()), 1)
        actual = self.read_catalog()
        scene = actual["scenes"][0]
        self.assertEqual(actual["products"], original["products"])
        self.assertEqual(actual["scenes"][1:], original["scenes"][1:])
        self.assertTrue(scene["available"])
        self.assertTrue(scene["modelUrl"].startswith("/content/rain/"))
        self.assertEqual(scene["modelProvenance"]["product"], "VARCO3D")
        self.assertEqual(scene["attribution"], "3D · VARCO 3D / 사운드 · VARCO Sound")
        self.assertEqual(scene["attributionEn"], "3D · VARCO 3D / Sound · VARCO Sound")
        self.assertEqual(scene["layers"][0]["provenance"]["product"], "VARCOSound")
        self.assertTrue(scene["layers"][0]["file"].startswith("content/rain/"))
        for index, layer in enumerate(scene["layers"]):
            for key in ("id", "label", "volume"):
                self.assertEqual(layer[key], original["scenes"][0]["layers"][index][key])
        self.assertFalse(scene["layers"][1]["available"])
        self.assertIsNone(scene["layers"][1]["file"])
        self.assertFalse(actual["releaseReady"])

    def test_scene_ready_rejects_rights_missing_file_hash_and_path_escape(self):
        self.module.import_assets(self.args())
        original = self.read_catalog()["scenes"][0]
        self.assertTrue(self.module.scene_ready(original))
        for field in ("provenance", "modelProvenance"):
            scene = copy.deepcopy(original)
            scene[field]["rightsConfirmed"] = False
            self.assertFalse(self.module.scene_ready(scene))
        scene = copy.deepcopy(original)
        scene["layers"][0]["provenance"]["rightsConfirmed"] = False
        self.assertFalse(self.module.scene_ready(scene))
        scene = copy.deepcopy(original)
        scene["modelUrl"] = "/../../../../synthetic-test-only.glb"
        self.assertFalse(self.module.scene_ready(scene))
        scene = copy.deepcopy(original)
        scene["layers"][0]["file"] = "content/missing.wav"
        self.assertFalse(self.module.scene_ready(scene))
        scene = copy.deepcopy(original)
        scene["layers"][0]["provenance"]["sha256"] = "0" * 64
        self.assertFalse(self.module.scene_ready(scene))
        (self.assets / original["layers"][0]["file"]).write_bytes(b"changed after import")
        self.assertFalse(self.module.scene_ready(original))

    def test_concurrent_cli_imports_preserve_every_scene(self):
        def invoke(scene):
            return subprocess.run([
                sys.executable, str(self.scripts / "import-assets.py"),
                "--scene", scene, "--model", str(self.model), "--audio", str(self.audio),
                "--source-note", "TEST ONLY: temporary synthetic fixture", "--rights-confirmed",
            ], capture_output=True, text=True, timeout=15)
        with ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(invoke, ("rain", "camp", "space")))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stderr)
        catalog = self.read_catalog()
        self.assertTrue(catalog["releaseReady"])
        self.assertTrue(all(self.module.scene_ready(scene) for scene in catalog["scenes"]))

    def test_preflight_debug_allows_pending_but_release_blocks_it(self):
        debug = self.run_preflight()
        release = self.run_preflight(release=True)
        self.assertEqual(debug.returncode, 0, debug.stdout + debug.stderr)
        self.assertIn("PENDING:", debug.stdout)
        self.assertNotEqual(release.returncode, 0)
        self.assertIn("Unverified or missing genuine VARCO assets", release.stdout)

    def test_preflight_rejects_claimed_availability_without_assets(self):
        catalog = self.read_catalog()
        catalog["scenes"][0]["available"] = True
        self.write_catalog(catalog)
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Scene marked available", result.stdout)

    def test_preflight_rejects_permissions_driving_metadata_and_wrong_qr_host(self):
        self.manifest.write_text(
            '<manifest xmlns:android="{}"><uses-permission android:name="android.permission.INTERNET" />'
            '<application><meta-data android:name="distractionOptimized" android:value="true" />'
            '</application></manifest>'.format(ANDROID_NS))
        catalog = self.read_catalog()
        catalog["products"]["sound"]["url"] = "https://sound.varco.ai.example.com/ko"
        self.write_catalog(catalog)
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        for message in ("must not introduce permissions", "must omit distractionOptimized", "Unexpected product QR destination"):
            self.assertIn(message, result.stdout)

    def test_preflight_rejects_duplicate_scene_ids(self):
        catalog = self.read_catalog()
        catalog["scenes"][1]["id"] = "rain"
        self.write_catalog(catalog)
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Duplicate scene IDs", result.stdout)

    def test_one_retained_scene_can_pass_release_with_all_evidence(self):
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args(audio=[str(self.audio)] * 3))
        self.assertTrue(self.read_catalog()["releaseReady"])
        self.write_release_evidence()
        # A selected app ID needs no separate company/Pleos approval field.
        evidence_path = self.root / "docs/release-evidence.json"
        evidence = json.loads(evidence_path.read_text())
        evidence.pop("approvedPackageName", None)
        evidence_path.write_text(json.dumps(evidence))
        result = self.run_preflight(release=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Preflight passed", result.stdout)
        self.assertNotIn("PENDING:", result.stdout)

    def test_preflight_still_blocks_missing_release_evidence_after_asset_import(self):
        for scene in ("rain", "camp", "space"):
            self.module.import_assets(self.args(scene=scene))
        result = self.run_preflight(release=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Unverified or missing genuine VARCO assets", result.stdout)
        self.assertIn("Release evidence missing: emulatorQaCompleted", result.stdout)

    def test_preflight_rejects_wrong_approved_package_name(self):
        self.write_release_evidence()
        evidence_path = self.root / "docs/release-evidence.json"
        evidence = json.loads(evidence_path.read_text())
        evidence["approvedPackageName"] = "com.example.wrong"
        evidence_path.write_text(json.dumps(evidence))
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Approved package name does not match", result.stdout)

    def test_package_free_manifest_uses_gradle_application_id_for_release(self):
        self.manifest.write_text('<manifest xmlns:android="{}"><application /></manifest>'.format(ANDROID_NS))
        (self.root / "app/build.gradle.kts").write_text(
            'android {\n    namespace = "com.example.namespace"\n    defaultConfig {\n'
            '        applicationId = "' + PACKAGE_NAME + '"\n    }\n}\n')
        catalog = self.read_catalog()
        catalog["scenes"] = catalog["scenes"][:1]
        self.write_catalog(catalog)
        self.module.import_assets(self.args())
        self.write_release_evidence()
        result = self.run_preflight(release=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Preflight passed", result.stdout)

    def test_present_gradle_without_application_id_does_not_fall_back_to_manifest(self):
        (self.root / "app/build.gradle.kts").write_text('android { namespace = "' + PACKAGE_NAME + '" }\n')
        self.write_release_evidence()
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must declare exactly one literal applicationId", result.stdout)


if __name__ == "__main__":
    unittest.main()
