import tempfile
import unittest
import lab


class RecordingMarkChecks(unittest.TestCase):
    def test_durable_mark_cooldown_bounds_missing_time_and_write_failure(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/RecordingMarkStore.java',
                         lab.ROOT / 'tests/RecordingMarkStoreCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.RecordingMarkStoreCheck'])
            self.assertIn(b'recording mark checks passed', result.stdout)
