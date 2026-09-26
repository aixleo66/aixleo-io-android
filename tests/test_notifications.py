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


class BackgroundServiceNotifications(unittest.TestCase):
    """09-24: "短信正在运行" / "米家正在运行" were forwarded ahead of the real message."""

    def test_constants_match_android_and_listener_uses_the_filter(self):
        policy = (lab.ROOT / 'app/src/NotificationPolicy.java').read_text(encoding='utf-8')
        # Android: Notification.FLAG_FOREGROUND_SERVICE = 0x00000040, CATEGORY_SERVICE = "service".
        self.assertIn('static final int FLAG_FOREGROUND_SERVICE=0x40;', policy)
        self.assertIn('static final String CATEGORY_SERVICE="service";', policy)
        listener = (lab.ROOT / 'app/src/PhoneNotifications.java').read_text(encoding='utf-8')
        # The filter result feeds eligible() as the "backgroundService" argument, not negated or dropped.
        self.assertIn(',p.getBoolean("include_silent",false),NotificationPolicy.backgroundService(n.flags,n.category)))return;', listener)
        self.assertNotIn('!NotificationPolicy.backgroundService', listener)

    def test_constants_equal_the_platform_values(self):
        # Checked against the android.jar the build compiles with (review 09-24): 64 and "service".
        s = lab.settings()
        out = subprocess.run([str(lab.tool(s, 'javap')), '-constants', '-cp', str(s['android_jar']), 'android.app.Notification'],
                             check=True, capture_output=True).stdout.decode('utf-8', 'replace')
        self.assertIn('int FLAG_FOREGROUND_SERVICE = 64;', out)
        self.assertIn('String CATEGORY_SERVICE = "service";', out)
