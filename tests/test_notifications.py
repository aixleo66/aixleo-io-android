import subprocess
import tempfile
import unittest
import lab

class NotificationChecks(unittest.TestCase):
    def test_unicode_limits_and_opt_in_filters(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory(prefix='rayneo-notification-') as tmp:
            subprocess.run([str(lab.tool(settings, 'javac')), '-encoding', 'UTF-8', '-d', tmp,
                            str(lab.ROOT / 'app/src/NotificationPolicy.java'),
                            str(lab.ROOT / 'app/src/NotificationIdentity.java'),
                            str(lab.ROOT / 'tests/NotificationPolicyCheck.java')], check=True, capture_output=True)
            result = subprocess.run([str(lab.tool(settings, 'java')), '-cp', tmp,
                                     'dev.xr.rayneo.probe.NotificationPolicyCheck'], check=True, capture_output=True)
            self.assertIn(b'notification checks passed', result.stdout)
