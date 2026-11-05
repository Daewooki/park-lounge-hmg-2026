#!/usr/bin/env python3
"""Validate packaged sources; distinguish a signed candidate from submission readiness."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
p = argparse.ArgumentParser()
mode = p.add_mutually_exclusive_group()
mode.add_argument('--release', action='store_true', help='Require complete release/submission evidence.')
mode.add_argument('--release-candidate', action='store_true', help='Require packaged assets; report pending submission evidence without attesting it.')
args = p.parse_args()
errors, pending = [], []
catalog = json.loads((ASSETS/'catalog.json').read_text())
manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = [e.get(ns+'name') for e in manifest.findall('uses-permission')]
allowed_permissions = {'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
                       'android.car.permission.CAR_POWERTRAIN'}
unexpected_permissions = set(permissions) - allowed_permissions
if unexpected_permissions: errors.append('Offline v1 must not introduce permissions outside media playback and the Park-only read gate: '+str(sorted(unexpected_permissions)))
application = manifest.find('application')
if application is not None:
    service = next((s for s in application.findall('service') if s.get(ns+'name') == '.PlaybackService'), None)
    if service is not None:
        if set(permissions) != allowed_permissions: errors.append('Media playback service requires exactly two foreground-service permissions and the normal CAR_POWERTRAIN read permission.')
        if application.get(ns+'appCategory') != 'audio': errors.append('Media playback app must declare the audio appCategory.')
        if service.get(ns+'foregroundServiceType') != 'mediaPlayback': errors.append('PlaybackService must declare mediaPlayback foregroundServiceType.')
        if not any(a.get(ns+'name') == 'android.media.browse.MediaBrowserService' for a in service.findall('intent-filter/action')):
            errors.append('PlaybackService must expose the standard MediaBrowserService action.')
for element in manifest.iter('meta-data'):
    if element.get(ns+'name') == 'distractionOptimized': errors.append('Parked-only app must omit distractionOptimized metadata.')
for relative in ['web/index.html']:
    if not (ASSETS/relative).is_file(): errors.append('Missing frontend: '+relative)
module_spec = importlib.util.spec_from_file_location('varco_import', ROOT/'scripts/import-assets.py')
module = importlib.util.module_from_spec(module_spec); module_spec.loader.exec_module(module)
scenes = catalog.get('scenes', [])
if not scenes: errors.append('No scenes in catalog.')
if len({s.get('id') for s in scenes}) != len(scenes): errors.append('Duplicate scene IDs.')
for scene in scenes:
    if not module.scene_ready(scene): pending.append('Unverified or missing genuine VARCO assets: '+str(scene.get('id')))
    if args.release_candidate and not module.scene_files_ready(scene):
        errors.append('Release candidate requires complete packaged assets with matching provenance hashes: '+str(scene.get('id')))
    development_ready = module.scene_development_ready(scene) and catalog.get('releaseReady') is False
    if scene.get('available') and not module.scene_ready(scene) and not development_ready:
        errors.append('Scene marked available without verified source files or an explicit development import: '+str(scene.get('id')))
total = sum(f.stat().st_size for f in ASSETS.rglob('*') if f.is_file())
if total > 550*1024*1024: errors.append('Packaged assets exceed 550 MiB budget; optimize before signing.')
expected = {'sound':'sound.varco.ai','threeD':'3d.varco.ai','api':'api.varco.ai'}
from urllib.parse import urlsplit
for key, host in expected.items():
    url = urlsplit(catalog.get('products',{}).get(key,{}).get('url',''))
    if url.scheme != 'https' or url.netloc != host: errors.append('Unexpected product QR destination: '+key)
release_file = ROOT/'docs/release-evidence.json'
release = json.loads(release_file.read_text()) if release_file.is_file() else {}
for key in ['assetReviewCompleted','emulatorQaCompleted','iarcEvidence','supportInboxConfirmed']:
    if not release.get(key): pending.append('Release evidence missing: '+key)
# A separately supplied company package reservation is optional, not a Pleos
# approval requirement. When present, it must still match the actual app ID.
approved_package = release.get('approvedPackageName')
gradle_file = ROOT/'app/build.gradle.kts'
application_id = manifest.get('package')
if gradle_file.is_file():
    application_ids = re.findall(r'^\s*applicationId\s*=\s*"([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)"', gradle_file.read_text(), re.MULTILINE)
    if len(application_ids) != 1:
        errors.append('app/build.gradle.kts must declare exactly one literal applicationId.')
        application_id = None
    else:
        application_id = application_ids[0]
if approved_package and approved_package != application_id:
    errors.append('Approved package name does not match the configured applicationId.')
for key in ['realDeviceQaCompleted','webviewCategoryConfirmed']:
    if not release.get(key): print('RECOMMENDED: Review before broad rollout:', key)
for key in ['privacyUrl','supportUrl']:
    value=release.get(key,'')
    if not isinstance(value,str) or not value.startswith('https://'): pending.append('Published URL missing: '+key)
for message in errors: print('ERROR:',message)
for message in pending: print('PENDING:',message)
print('Packaged assets: {:.2f} MiB'.format(total/1024/1024))
if errors or (args.release and pending): sys.exit(1)
if args.release_candidate:
    print('Release-candidate technical preflight passed; submission readiness and rights are not attested by this check.')
else:
    print('Debug source preflight passed; pending items prevent release.' if pending else 'Preflight passed.')
