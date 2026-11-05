#!/usr/bin/env python3
"""Import genuine, locally exported VARCO assets into the offline app catalog.

This tool records provenance with either an explicit rights confirmation or an
unverified development-only import. It does not verify the generating service
or establish a license by examining media bytes.
No network requests are made and no credentials are needed.
"""

import argparse
import copy
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import wave


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
CATALOG = ASSETS / "catalog.json"


def fail(message):
    raise ValueError(message)


def input_file(value, extension):
    path = Path(value).expanduser()
    if not path.is_absolute():
        fail("Source files must use absolute paths.")
    path = path.resolve(strict=True)
    if not path.is_file() or path.suffix.lower() != extension:
        fail("Expected a local {} file: {}".format(extension, path))
    return path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_glb(path):
    size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(12)
        if len(header) != 12:
            fail("GLB is too short.")
        magic, version, declared_size = struct.unpack("<4sII", header)
        if magic != b"glTF" or version != 2:
            fail("Model must be a GLB version 2 file.")
        if declared_size != size:
            fail("GLB declared size does not match its actual size.")
        document = None
        while stream.tell() < size:
            chunk_header = stream.read(8)
            if len(chunk_header) != 8:
                fail("Truncated GLB chunk header.")
            length, kind = struct.unpack("<II", chunk_header)
            if length % 4 or length > size - stream.tell():
                fail("Invalid GLB chunk length.")
            if document is None:
                if kind != 0x4E4F534A:
                    fail("The first GLB chunk must be JSON.")
                document = json.loads(stream.read(length).decode("utf-8"))
            else:
                stream.seek(length, os.SEEK_CUR)
        if not isinstance(document, dict) or document.get("asset", {}).get("version") != "2.0":
            fail("GLB JSON does not declare glTF 2.0.")
        # The bundled viewer has no Draco, Meshopt or KTX2/Basis decoder. Reject
        # optional usage as well: this importer does not prove fallback validity.
        unsupported = {"KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu"}
        for field in ("extensionsUsed", "extensionsRequired"):
            extensions = document.get(field, [])
            if not isinstance(extensions, list) or not all(isinstance(item, str) for item in extensions):
                fail("GLB {} must be an array of extension names.".format(field))
            rejected = unsupported.intersection(extensions)
            if rejected:
                fail("GLB requires unavailable decoders ({}); export uncompressed GLB with embedded PNG/JPEG textures.".format(
                    ", ".join(sorted(rejected))))
        # External files would be missing from the offline APK. Embedded data
        # URIs are self-contained and are therefore allowed.
        for collection in ("buffers", "images"):
            for resource in document.get(collection, []):
                uri = resource.get("uri")
                if uri is not None and not uri.startswith("data:"):
                    fail("GLB references external resources; export a self-contained GLB.")
    return {"format": "glb", "glbVersion": version, "bytes": size, "sha256": sha256(path)}


def inspect_wav(path):
    with wave.open(str(path), "rb") as audio:
        frames = audio.getnframes()
        rate = audio.getframerate()
        channels = audio.getnchannels()
        sample_bytes = audio.getsampwidth()
        if frames <= 0 or rate <= 0 or channels <= 0 or sample_bytes <= 0:
            fail("WAV must contain a positive duration of audio.")
        if audio.getcomptype() != "NONE":
            fail("Use an uncompressed PCM WAV export.")
        # A valid header with a truncated data chunk must not pass import.
        expected = frames * channels * sample_bytes
        actual = 0
        while True:
            block = audio.readframes(16384)
            if not block:
                break
            actual += len(block)
        if actual != expected:
            fail("WAV data is incomplete: expected {} bytes, found {}.".format(expected, actual))
    return {
        "format": "wav", "bytes": path.stat().st_size, "sha256": sha256(path),
        "durationSeconds": round(frames / rate, 6), "sampleRateHz": rate,
        "channels": channels, "sampleWidthBytes": sample_bytes,
    }


def import_provenance(source_note, stamp, rights_confirmed):
    record = {
        "sourceNote": source_note, "rightsConfirmed": rights_confirmed,
        "importedAt": stamp,
        "provenanceType": ("operator-attestation" if rights_confirmed
                           else "development-import-unverified-rights"),
    }
    if rights_confirmed:
        record["rightsConfirmedAt"] = stamp
    return record


def provenance(product, source_note, stamp, metadata, rights_confirmed=True):
    return dict(metadata, product=product,
                **import_provenance(source_note, stamp, rights_confirmed))


def atomic_json(path, document):
    descriptor, temporary = tempfile.mkstemp(prefix=".catalog-", suffix=".json", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(document, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish_file(source, destination, expected_hash):
    """Publish content-addressed files before atomically changing references."""
    if destination.exists():
        if sha256(destination) != expected_hash:
            fail("Existing content-addressed asset has unexpected content.")
        return
    descriptor, temporary = tempfile.mkstemp(prefix=".asset-", dir=str(destination.parent))
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as input_stream:
            shutil.copyfileobj(input_stream, output)
            output.flush()
            os.fsync(output.fileno())
        if sha256(Path(temporary)) != expected_hash:
            fail("Source file changed during import; retry after export finishes.")
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def scene_files_ready(scene):
    """Check packaged bytes independently of permission to distribute them."""
    if not scene.get("available"):
        return False
    assets = [(scene.get("modelUrl"), scene.get("modelProvenance", {}))]
    layers = [layer for layer in scene.get("layers", []) if layer.get("available")]
    if not layers:
        return False
    assets.extend((layer.get("file"), layer.get("provenance", {})) for layer in layers)
    for relative, record in assets:
        if not isinstance(relative, str) or not record.get("sourceNote"):
            return False
        path = (ASSETS / relative.lstrip("/")).resolve()
        if ASSETS.resolve() not in path.parents or not path.is_file():
            return False
        if sha256(path) != record.get("sha256"):
            return False
    return True


def scene_provenance_records(scene):
    return [scene.get("provenance", {}), scene.get("modelProvenance", {})] + [
        layer.get("provenance", {}) for layer in scene.get("layers", []) if layer.get("available")
    ]


def scene_ready(scene):
    """Release requires verified files and explicit rights on every asset."""
    if scene.get("assetStatus") == "imported-unverified" or not scene_files_ready(scene):
        return False
    return all(record.get("rightsConfirmed") is True
               and record.get("provenanceType") != "development-import-unverified-rights"
               for record in scene_provenance_records(scene))


def scene_development_ready(scene):
    """Debug accepts explicit pending records alongside individually confirmed ones."""
    if scene.get("assetStatus") != "imported-unverified" or not scene_files_ready(scene):
        return False
    def valid_record(record):
        if not record.get("sourceNote"):
            return False
        if record.get("rightsConfirmed") is True:
            return (record.get("provenanceType") == "operator-attestation"
                    and isinstance(record.get("rightsConfirmedAt"), str)
                    and bool(record["rightsConfirmedAt"].strip()))
        return (record.get("rightsConfirmed") is False
                and record.get("provenanceType") == "development-import-unverified-rights"
                and "rightsConfirmedAt" not in record)
    return all(valid_record(record) for record in scene_provenance_records(scene))


def import_assets(args):
    # Missing catalog is deliberately a read-only failure. Never create one.
    if not CATALOG.is_file():
        fail("Catalog is not present; no files changed: {}".format(CATALOG))
    rights_confirmed = bool(getattr(args, "rights_confirmed", False))
    development_only = bool(getattr(args, "development_only", False))
    if rights_confirmed == development_only:
        fail("Choose exactly one of --rights-confirmed or --development-only; development imports cannot be released.")
    if not 1 <= len(args.audio) <= 3:
        fail("Provide one to three --audio WAV files.")
    source_note = args.source_note.strip()
    if not source_note:
        fail("--source-note must describe the genuine VARCO generation/export provenance.")

    model = input_file(args.model, ".glb")
    audios = [input_file(value, ".wav") for value in args.audio]
    model_info = inspect_glb(model)
    audio_info = [inspect_wav(path) for path in audios]
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")

    # A stable sibling lock survives atomic replacement of the catalog inode.
    with (CATALOG.parent / ".catalog.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not CATALOG.is_file():
            fail("Catalog disappeared before import; no assets copied.")
        with CATALOG.open(encoding="utf-8") as stream:
            original = json.load(stream)
        catalog = copy.deepcopy(original)
        scenes = catalog.get("scenes")
        if not isinstance(scenes, list):
            fail("Catalog must contain a scenes array.")
        matches = [scene for scene in scenes if scene.get("id") == args.scene]
        if len(matches) != 1:
            fail("Catalog must contain exactly one matching scene.")
        scene = matches[0]
        layers = scene.get("layers")
        if not isinstance(layers, list) or len(layers) < len(audios):
            fail("Catalog has too few predefined layers for the supplied audio files.")
        if not all(isinstance(layer, dict) for layer in layers):
            fail("Catalog layers must be objects.")

        destination = ASSETS / "content" / args.scene
        destination.mkdir(parents=True, exist_ok=True)
        model_name = "model-{}.glb".format(model_info["sha256"][:16])
        publish_file(model, destination / model_name, model_info["sha256"])
        model_path = "content/{}/{}".format(args.scene, model_name)
        scene["modelUrl"] = "/" + model_path
        scene["modelProvenance"] = provenance("VARCO3D", source_note, stamp, model_info, rights_confirmed)

        for index, layer in enumerate(layers):
            if index >= len(audios):
                layer["available"] = False
                layer["file"] = None
                layer.pop("provenance", None)
                continue
            info = audio_info[index]
            name = "audio-{}-{}.wav".format(index + 1, info["sha256"][:16])
            publish_file(audios[index], destination / name, info["sha256"])
            layer["file"] = "content/{}/{}".format(args.scene, name)
            layer["available"] = True
            layer["provenance"] = provenance("VARCOSound", source_note, stamp, info, rights_confirmed)

        scene["available"] = True
        scene["provenance"] = import_provenance(source_note, stamp, rights_confirmed)
        scene["assetStatus"] = "imported" if rights_confirmed else "imported-unverified"
        scene["attribution"] = "3D · VARCO 3D / 사운드 · VARCO Sound"
        scene["attributionEn"] = "3D · VARCO 3D / Sound · VARCO Sound"
        catalog["releaseReady"] = not development_only and bool(scenes) and all(scene_ready(item) for item in scenes)
        # UI labels, volumes, scene copy, IDs and all other scenes stay intact.
        atomic_json(CATALOG, catalog)
    return len(audios)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scene", required=True, choices=("rain", "shore", "fireside", "moon", "camp", "space"))
    parser.add_argument("--model", required=True, help="Absolute path to a genuine VARCO GLB export")
    parser.add_argument("--audio", action="append", required=True, help="Absolute WAV path; repeat up to three times")
    parser.add_argument("--source-note", required=True, help="Generation/export provenance, without secrets or signed URLs")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--rights-confirmed", action="store_true", help="Attest that all supplied assets may be distributed in this app")
    mode.add_argument("--development-only", action="store_true", help="Import for internal testing without attesting distribution rights; release stays blocked")
    args = parser.parse_args()
    try:
        count = import_assets(args)
    except (OSError, ValueError, KeyError, TypeError, wave.Error, EOFError) as error:
        print("Import failed: {}".format(error), file=sys.stderr)
        return 1
    print("Imported scene '{}': one GLB, {} WAV layer(s).".format(args.scene, count))
    if args.development_only:
        print("Development import: distribution rights remain unverified; release is blocked.")
    else:
        print("Provenance is recorded as an operator attestation; review assets before release.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
