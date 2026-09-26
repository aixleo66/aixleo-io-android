import subprocess
import tempfile
import unittest
from unittest.mock import patch
import lab
import session


class LabDisplayChecks(unittest.TestCase):
    def test_lifecycle_stale_reply_timeout_interruption_and_visibility(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([str(lab.tool(settings, 'javac')), '-encoding', 'UTF-8', '-d', tmp,
                            str(lab.ROOT / 'app/src/LabDisplayTrial.java'),
                            str(lab.ROOT / 'tests/LabDisplayTrialCheck.java')], check=True, capture_output=True)
            result = subprocess.run([str(lab.tool(settings, 'java')), '-cp', tmp,
                                     'dev.xr.rayneo.probe.LabDisplayTrialCheck'], check=True, capture_output=True)
            self.assertIn(b'checks passed', result.stdout)

    def test_daily_package_rejected_before_device_access(self):
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaises(ValueError):
                session.send({}, 'unused', 'lab-display-trial', package=lab.PACKAGE)
            adb.assert_not_called()

    def test_failed_candidate_is_quarantined_before_device_access(self):
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaises(RuntimeError):
                session.send({}, 'unused', 'lab-display-trial', package=lab.LAB_PACKAGE)
            adb.assert_not_called()
