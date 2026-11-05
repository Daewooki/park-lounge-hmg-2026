#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "/Applications/Android Studio 2.app/Contents/jbr/Contents/Home" "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
mkdir -p .build/tests
python3 - <<'PY'
import json
from pathlib import Path
c = json.loads(Path('app/src/main/assets/catalog.json').read_text())
Path('.build/tests/qr-payloads.txt').write_text('\n'.join(p['url'] for p in c['products'].values())+'\n')
PY
javac --release 17 -encoding UTF-8 -cp app/libs/zxing-core-3.5.3.jar -d .build/tests \
  app/src/main/java/ai/nc/varcospaces/SafetyPolicy.java tests/CorePolicyTest.java
java -cp .build/tests:app/libs/zxing-core-3.5.3.jar CorePolicyTest .build/tests/qr-payloads.txt
python3 -m unittest discover -s tests -p 'test_*.py' -v
node --check app/src/main/assets/web/app.js
node --input-type=module --check < app/src/main/assets/web/scene-view.js
python3 scripts/preflight.py
node tests/test_web_mixer.js

node tests/test_scene_view.mjs
