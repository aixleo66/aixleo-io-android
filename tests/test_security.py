import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch
import lab


class SecurityChecks(unittest.TestCase):
    def test_connection_diagnostics_do_not_retain_raw_vendor_messages(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/VendorConnectionDiagnostics.java', lab.ROOT / 'tests/ConnectionDiagnosticCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.ConnectionDiagnosticCheck'])
            self.assertIn(b'diagnostic redaction checks passed', result.stdout)

    def test_pairing_mode_cannot_reuse_stale_bond_for_business_readiness(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/ConnectionReadiness.java', lab.ROOT / 'tests/ConnectionReadinessCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.ConnectionReadinessCheck'])
            self.assertIn(b'connection readiness checks passed', result.stdout)

    def test_pairing_mode_retry_is_scoped_to_same_target_and_confirmed_error(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/ConnectionAttemptPolicy.java', lab.ROOT / 'tests/ConnectionAttemptPolicyCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.ConnectionAttemptPolicyCheck'])
            self.assertIn(b'connection attempt policy checks passed', result.stdout)

    def test_cancelled_service_request_cannot_cancel_replacement_owner(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/ServiceStartGate.java', lab.ROOT / 'tests/ServiceStartGateCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.ServiceStartGateCheck'])
            self.assertIn(b'service request ownership checks passed', result.stdout)

    def test_current_diagnostics_reject_shell_execution_before_device_access(self):
        self.assertFalse(lab.legacy_diagnostic_launch_enabled())
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaisesRegex(RuntimeError, '已关闭外部诊断入口'):
                lab.run_device({}, 'explicit-device', execute=True)
            adb.assert_not_called()

    def test_only_launcher_and_system_protected_listener_are_exported(self):
        root = ET.parse(lab.ROOT / 'app/AndroidManifest.xml').getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        application = root.find('application')
        for component in application:
            if component.tag not in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
                continue
            name = component.get(ns + 'name')
            if name == '.CloudActivity':
                self.assertEqual(component.get(ns + 'exported'), 'true')
            elif name == '.PhoneNotifications':
                self.assertEqual(component.get(ns + 'permission'), 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE')
            elif name == '.CompanionPresence':
                # Must be exported for the system to bind it, and is guarded the same way
                # PhoneNotifications is: a signature-level permission only the system holds.
                self.assertEqual(component.get(ns + 'exported'), 'true')
                self.assertEqual(component.get(ns + 'permission'), 'android.permission.BIND_COMPANION_DEVICE_SERVICE')
            else:
                self.assertEqual(component.get(ns + 'exported'), 'false', name)

    def test_rejected_and_stale_instances_cannot_release_another_session(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/SessionOwnership.java', lab.ROOT / 'tests/SessionOwnershipCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.SessionOwnershipCheck'])
            self.assertIn(b'session ownership checks passed', result.stdout)
