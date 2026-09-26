import tempfile
import unittest
import lab


class WakeSettingsChecks(unittest.TestCase):
    def test_wire_contract_and_unrelated_fields(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/RayNeoWakeSettings.java',
                         lab.ROOT / 'tests/RayNeoWakeSettingsCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                  'dev.xr.rayneo.probe.RayNeoWakeSettingsCheck'])
            self.assertIn(b'checks passed', result.stdout)
