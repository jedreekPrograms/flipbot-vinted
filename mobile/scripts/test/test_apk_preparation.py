from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ApkPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy2(ROOT / 'App.tsx', self.root / 'App.tsx')
        shutil.copytree(ROOT / 'scripts', self.root / 'scripts', ignore=shutil.ignore_patterns('__pycache__', 'test'))

    def run_patch(self, name, succeeds=True):
        result = subprocess.run([sys.executable, str(self.root / 'scripts' / name)], capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)

    def prepare(self):
        self.run_patch('patch_mobile_ui.py')
        self.run_patch('patch_android_system_bars.py')

    def test_full_apk_sequence_can_run_again_without_changing_its_output(self):
        self.prepare()
        prepared = (self.root / 'App.tsx').read_bytes()
        self.prepare()
        self.assertEqual(prepared, (self.root / 'App.tsx').read_bytes())
        self.assertIn(b'backgroundColor="#ffffff" translucent />', prepared)

    def test_each_step_is_repeatable_and_bad_input_is_not_written(self):
        self.run_patch('patch_mobile_ui.py')
        first = (self.root / 'App.tsx').read_bytes()
        self.run_patch('patch_mobile_ui.py')
        self.assertEqual(first, (self.root / 'App.tsx').read_bytes())
        self.run_patch('patch_android_system_bars.py')
        second = (self.root / 'App.tsx').read_bytes()
        self.run_patch('patch_android_system_bars.py')
        self.assertEqual(second, (self.root / 'App.tsx').read_bytes())
        for name in ('patch_mobile_ui.py', 'patch_android_system_bars.py'):
            (self.root / 'App.tsx').write_text('unrecognized source', encoding='utf-8')
            self.run_patch(name, succeeds=False)
            self.assertEqual('unrecognized source', (self.root / 'App.tsx').read_text(encoding='utf-8'))
