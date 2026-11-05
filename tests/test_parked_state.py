"""Compile production ParkedState against API 34 and test its actual monotonic lease."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ParkedStateHostTests(unittest.TestCase):
    def test_initial_read_unavailable_timeout_expiry_stale_event_and_reconnect(self):
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
        jars = [sdk / 'platforms/android-34/android.jar', sdk / 'platforms/android-34/optional/android.car.jar']
        candidates = [Path(os.environ['JAVA_HOME'])] if os.environ.get('JAVA_HOME') else []
        candidates += [Path('/Applications/Android Studio 2.app/Contents/jbr/Contents/Home'),
                       Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')]
        java_home = next((p for p in candidates if (p / 'bin/javac').is_file()), None)
        javac = str(java_home / 'bin/javac') if java_home else shutil.which('javac')
        java = str(java_home / 'bin/java') if java_home else shutil.which('java')
        if not all(p.is_file() for p in jars) or not javac or not java:
            self.skipTest('Android Automotive API 34 and a JDK are required')
        classpath = os.pathsep.join(map(str, jars))
        production = ROOT / 'app/src/main/java/ai/nc/varcospaces'
        sources = [production / 'SafetyPolicy.java', production / 'ParkedState.java', ROOT / 'tests/GearLeaseTest.java']
        with tempfile.TemporaryDirectory(prefix='varco-gear-jvm-') as temporary:
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                                       '-Xlint:-options', '-cp', classpath, '-d', temporary, *map(str, sources)],
                                      capture_output=True, text=True, timeout=60)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run([java, '-cp', temporary + os.pathsep + classpath,
                                     'ai.nc.varcospaces.GearLeaseTest'], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Gear revalidation tests passed:', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
