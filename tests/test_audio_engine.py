"""Compile the real audio engine; exercise its PCM reader/mix math on host JVM."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class AudioEngineHostTests(unittest.TestCase):
    def test_pcm_validation_loop_frames_mixing_and_packaged_audio(self):
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
        android = sdk / 'platforms/android-34/android.jar'
        candidates = [Path(os.environ['JAVA_HOME'])] if os.environ.get('JAVA_HOME') else []
        candidates += [Path('/Applications/Android Studio 2.app/Contents/jbr/Contents/Home'), Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')]
        java_home = next((p for p in candidates if (p / 'bin/javac').is_file()), None)
        javac = str(java_home / 'bin/javac') if java_home else shutil.which('javac')
        java = str(java_home / 'bin/java') if java_home else shutil.which('java')
        if not android.is_file() or not javac or not java:
            self.skipTest('Android API 34 and a JDK are required for the audio host test')
        car = sdk / 'platforms/android-34/optional/android.car.jar'
        if not car.is_file():
            self.skipTest('Android Automotive API 34 is required to compile the playback service')
        classpath = os.pathsep.join(map(str, [android, car, ROOT / 'app/libs/zxing-core-3.5.3.jar']))
        sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + [
            ROOT / 'tests/AudioEngineTest.java', ROOT / 'tests/PlaybackLifecycleTest.java', ROOT / 'tests/SceneCatalogTest.java']
        files = []
        assets = ROOT / 'app/src/main/assets'
        for scene in json.loads((assets / 'catalog.json').read_text()).get('scenes', []):
            for layer in scene.get('layers', []):
                if layer.get('available') and layer.get('file'):
                    files.append(str(assets / layer['file']))
        with tempfile.TemporaryDirectory(prefix='varco-audio-jvm-') as temporary:
            subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
                            '-cp', classpath, '-d', temporary, *map(str, sources)], check=True, capture_output=True, text=True, timeout=60)
            result = subprocess.run([java, '-cp', temporary + os.pathsep + classpath, 'ai.nc.varcospaces.AudioEngineTest', *files],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Audio PCM and mixer tests passed:', result.stdout)
            print(result.stdout.strip())
            lifecycle = subprocess.run([java, '-cp', temporary + os.pathsep + classpath, 'ai.nc.varcospaces.PlaybackLifecycleTest'],
                                       capture_output=True, text=True, timeout=30)
            self.assertEqual(lifecycle.returncode, 0, lifecycle.stdout + lifecycle.stderr)
            self.assertIn('Playback lifecycle tests passed:', lifecycle.stdout)
            print(lifecycle.stdout.strip())
            scenes = subprocess.run([java, '-cp', temporary + os.pathsep + classpath, 'ai.nc.varcospaces.SceneCatalogTest'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(scenes.returncode, 0, scenes.stdout + scenes.stderr)
            self.assertIn('Scene catalog and settings tests passed:', scenes.stdout)
            print(scenes.stdout.strip())

if __name__ == '__main__':
    unittest.main()
