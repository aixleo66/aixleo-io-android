import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import lab


class PortabilityChecks(unittest.TestCase):
    def test_repeated_clock_does_not_overwrite_evidence(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)), \
                patch.object(lab, 'datetime') as clock:
            clock.now.return_value.strftime.return_value = 'same-clock'
            first = lab.fresh_output_dir('runs')
            (first / 'evidence.txt').write_text('preserve')
            second = lab.fresh_output_dir('runs')
            self.assertNotEqual(first, second)
            self.assertTrue(second.is_dir())
            self.assertEqual((first / 'evidence.txt').read_text(), 'preserve')

    def test_relative_config_uses_repo_not_current_directory(self):
        with tempfile.TemporaryDirectory(prefix='rayneo space ') as tmp:
            root = Path(tmp)
            config = root / 'config.local.json'
            config.write_text(json.dumps({'sample': 'private/samples/test.apk', 'adb': 'my tools/adb.exe'}))
            with patch.object(lab, 'ROOT', root):
                s = lab.settings()
                self.assertEqual(s['sample'], root / 'private/samples/test.apk')
                self.assertEqual(s['adb'], root / 'my tools/adb.exe')

    def test_private_corruption_detected(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)):
            root = Path(tmp) / 'private'
            root.mkdir()
            f = root / 'input.apk'
            f.write_bytes(b'original')
            lab.write_json(root / 'manifest.json', {'files': [{'path': 'input.apk', 'sha256': lab.digest(f)}]})
            self.assertEqual(lab.check_private()['status'], 'private_verified')
            f.write_bytes(b'changed')
            self.assertEqual(lab.check_private()['status'], 'blocked_or_failed')

    def test_zip_escape_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            z = Path(tmp) / 'unsafe.zip'
            with zipfile.ZipFile(z, 'w') as f:
                f.writestr('../escape.txt', 'no')
            with self.assertRaises(RuntimeError):
                lab.safe_extract(z, Path(tmp) / 'output')
            self.assertFalse((Path(tmp) / 'escape.txt').exists())

    def test_no_execute_never_installs(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)), \
                patch.object(lab, 'latest_output', return_value=Path(tmp)), \
                patch.object(lab, 'verify'), patch.object(lab, 'digest', return_value='hash'):
            calls = []
            def fake(s, serial, *args, **kw):
                calls.append(args)
                return subprocess.CompletedProcess(args, 0, b'device' if args == ('get-state',) else b'36', b'')
            with patch.object(lab, 'adb_result', side_effect=fake):
                r = lab.run_device({}, 'explicit-device')
                observed = lab.run_device({}, 'explicit-device', target_address='00:11:22:33:44:55')
                discovered = lab.run_device({}, 'explicit-device', target_address='00:11:22:33:44:55', sdk_mode='sdk-discover')
                connected = lab.run_device({}, 'explicit-device', target_address='00:11:22:33:44:55', sdk_mode='sdk-connect', pairing_ready=True)
                status = lab.run_device({}, 'explicit-device', target_address='00:11:22:33:44:55', sdk_mode='sdk-status', pairing_ready=True)
                text = lab.run_device({}, 'explicit-device', target_address='00:11:22:33:44:55', sdk_mode='sdk-text', pairing_ready=True)
            self.assertEqual(r['status'], 'ready')
            self.assertEqual(observed['status'], 'ready')
            self.assertEqual(discovered['status'], 'ready')
            self.assertEqual(connected['status'], 'ready')
            self.assertEqual(status['status'], 'ready')
            self.assertEqual(text['status'], 'ready')
            self.assertFalse(any('install' in x or 'am' in x or 'grant' in x for x in calls))

    @patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=True)
    def test_legacy_observation_scan_failure_remains_failure_and_stops_probe(self, _legacy):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)), \
                patch.object(lab, 'latest_output', return_value=Path(tmp)), \
                patch.object(lab, 'verify'), patch.object(lab, 'digest', return_value='hash'):
            calls = []
            def fake(s, serial, *args, **kw):
                calls.append(args)
                code, out = 0, b''
                if args == ('get-state',): out = b'device'
                elif 'getprop' in args: out = b'36'
                elif args[0] == 'install': out = b'Success'
                elif 'cat' in args:
                    out = b'{"status":"failed","error":"BLE scan error 2","stages":[{}]}'
                elif 'pidof' in args: code = 1
                return subprocess.CompletedProcess(args, code, out, b'')
            with patch.object(lab, 'adb_result', side_effect=fake), \
                    patch.object(lab, 'prepare_observation_permissions', return_value={'user': 0}):
                r = lab.run_device({}, 'explicit-device', True, target_address='00:11:22:33:44:55')
            self.assertEqual(r['status'], 'failed')
            self.assertTrue(r['process_stopped'])
            self.assertIn('BLE scan error', r['probe_result']['error'])
            self.assertTrue(any(lab.PACKAGE + '/.ObserveActivity' in x for x in calls))

    def test_invalid_observation_address_never_contacts_device(self):
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaises(ValueError):
                lab.run_device({}, 'explicit-device', True, target_address='invalid; input')
            adb.assert_not_called()

    def test_sdk_mode_requires_target_before_any_device_action(self):
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaises(ValueError):
                lab.run_device({}, 'explicit-device', True, sdk_mode='sdk-connect', pairing_ready=True)
            with self.assertRaises(ValueError):
                lab.run_device({}, 'explicit-device', True, target_address='00:11:22:33:44:55', pairing_ready=True)
            adb.assert_not_called()

    @patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=True)
    def test_legacy_sdk_connect_preflight_block_is_not_success(self, _legacy):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)), \
                patch.object(lab, 'latest_output', return_value=Path(tmp)), \
                patch.object(lab, 'verify'), patch.object(lab, 'digest', return_value='hash'):
            calls = []
            def fake(s, serial, *args, **kw):
                calls.append(args)
                code, out = 0, b''
                if args == ('get-state',): out = b'device'
                elif 'getprop' in args: out = b'36'
                elif args[0] == 'install': out = b'Success'
                elif 'cat' in args:
                    out = b'{"status":"blocked_or_failed","connect_attempted":false,"stages":[{}]}'
                elif 'pidof' in args: code = 1
                return subprocess.CompletedProcess(args, code, out, b'')
            with patch.object(lab, 'adb_result', side_effect=fake), \
                    patch.object(lab, 'prepare_observation_permissions', return_value={'user': 0}):
                r = lab.run_device({}, 'explicit-device', True, target_address='00:11:22:33:44:55', sdk_mode='sdk-connect')
            self.assertEqual(r['status'], 'blocked_or_failed')
            self.assertFalse(r['probe_result']['connect_attempted'])
            self.assertTrue(r['process_stopped'])
            launch = next(c for c in calls if 'start' in c)
            self.assertEqual(launch[launch.index('pairing_ready') + 1], 'false')

    def test_permissions_require_current_user_readback(self):
        calls = []
        def adb(*args):
            calls.append(args)
            if args == ('shell', 'am', 'get-current-user'): return '0'
            if 'dumpsys' in args:
                return ('  User 0: installed=true\n'
                        '    android.permission.BLUETOOTH_CONNECT: granted=false\n'
                        '    android.permission.BLUETOOTH_SCAN: granted=false\n'
                        '  User 999: installed=true\n'
                        '    android.permission.BLUETOOTH_CONNECT: granted=true\n'
                        '    android.permission.BLUETOOTH_SCAN: granted=true\n')
            return ''
        with patch.object(lab.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, '权限'):
                lab.prepare_observation_permissions(adb)
        self.assertEqual(sum('dumpsys' in c for c in calls), 3)

    @patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=True)
    def test_legacy_disconnected_cleanup_is_not_success(self, _legacy):
        with tempfile.TemporaryDirectory() as tmp, patch.object(lab, 'ROOT', Path(tmp)), \
                patch.object(lab, 'latest_output', return_value=Path(tmp)), \
                patch.object(lab, 'verify'), patch.object(lab, 'digest', return_value='hash'):
            state_calls = 0
            def fake(s, serial, *args, **kw):
                nonlocal state_calls
                if args == ('get-state',):
                    state_calls += 1
                    if state_calls > 1:
                        raise RuntimeError('device disconnected')
                    out = b'device'
                elif 'getprop' in args:
                    out = b'36'
                elif args[0] == 'install':
                    out = b'Success'
                elif 'cat' in args:
                    out = b'{"status":"initialization_passed","stages":[{}]}'
                else:
                    out = b''
                return subprocess.CompletedProcess(args, 0, out, b'')
            with patch.object(lab, 'adb_result', side_effect=fake):
                r = lab.run_device({}, 'explicit-device', True)
            self.assertFalse(r['process_stopped'])
            self.assertIn('disconnected', r['cleanup_error'])


if __name__ == '__main__':
    unittest.main()
