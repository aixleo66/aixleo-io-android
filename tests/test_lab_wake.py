import tempfile
import unittest
from unittest.mock import patch
import lab
import session


class WakeTrialChecks(unittest.TestCase):
    def test_controller_roundtrip(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/LabWakeTrial.java',
                         lab.ROOT / 'app/src/LabSettingsTrial.java',
                         lab.ROOT / 'app/src/RayNeoWakeSettings.java',
                         lab.ROOT / 'tests/LabWakeTrialCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                  'dev.xr.rayneo.probe.LabWakeTrialCheck'])
            self.assertIn(b'checks passed', result.stdout)

    def test_cli_and_daily_rejection(self):
        for kind in ('lab-wake-trial', 'lab-wake-restore', 'lab-wake-low', 'lab-wake-high'):
            with patch.object(lab, 'adb_result') as adb:
                with self.assertRaises(ValueError):
                    session.send({}, 'unused', kind, package=lab.PACKAGE)
                adb.assert_not_called()
            with patch('sys.argv', ['session.py', kind, '--serial', 'unused', '--profile', 'sdk-lab']), \
                    patch.object(lab, 'settings', return_value={}), \
                    patch.object(session, 'send', return_value={'status': 'completed'}) as send, \
                    patch('builtins.print'):
                self.assertEqual(session.main(), 0)
                self.assertEqual(send.call_args.args[2], kind)
                self.assertEqual(send.call_args.kwargs['package'], lab.LAB_PACKAGE)
                self.assertGreaterEqual(send.call_args.kwargs['timeout'], 90)
