import tempfile
import unittest
from unittest.mock import patch
import lab
import session

class BrightnessChecks(unittest.TestCase):
    def test_bounded_trial_restore_and_failure(self):
        s=lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',directory,
                         lab.ROOT/'app/src/LabBrightnessTrial.java',lab.ROOT/'tests/LabBrightnessTrialCheck.java'])
            self.assertIn(b'checks passed',lab.command([lab.tool(s,'java'),'-cp',directory,'dev.xr.rayneo.probe.LabBrightnessTrialCheck']).stdout)
    def test_daily_rejected_without_device_access(self):
        with patch.object(lab,'adb_result') as adb:
            for kind in ['lab-brightness-trial','lab-brightness-restore','lab-brightness-min','lab-brightness-max']:
                with self.assertRaises(ValueError):session.send({},'unused',kind,package=lab.PACKAGE)
            adb.assert_not_called()

    def test_cli_routes_all_brightness_commands(self):
        for kind in ['lab-brightness-trial','lab-brightness-restore','lab-brightness-min','lab-brightness-max']:
            with patch('sys.argv',['session.py',kind,'--serial','test','--profile','sdk-lab']), patch.object(lab,'settings',return_value={}), patch.object(session,'send',return_value={'status':'completed'}) as send, patch('builtins.print'):
                self.assertEqual(session.main(),0)
                self.assertEqual(send.call_args.args[2],kind)
                self.assertEqual(send.call_args.kwargs['package'],lab.LAB_PACKAGE)
