import tempfile
import unittest
import lab


class CompanionAssociationChecks(unittest.TestCase):
    def test_association_is_never_requested_twice_for_one_device(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/CompanionAssociationPolicy.java',
                         lab.ROOT / 'tests/CompanionAssociationPolicyCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.CompanionAssociationPolicyCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_manifest_declares_companion_permissions(self):
        manifest = (lab.ROOT / 'app/AndroidManifest.xml').read_text(encoding='utf-8')
        self.assertIn('android.software.companion_device_setup', manifest)
        for permission in ('REQUEST_COMPANION_RUN_IN_BACKGROUND',
                           'REQUEST_COMPANION_USE_DATA_IN_BACKGROUND',
                           # Plural SERVICES is the platform name; the singular spelling used until
                           # 09-23 matched no permission and was never granted (dumpsys on device).
                           'REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND'):
            self.assertIn('android.permission.' + permission, manifest)
