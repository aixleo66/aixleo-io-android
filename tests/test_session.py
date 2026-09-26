import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import lab
import session
import display_observer


class SessionTests(unittest.TestCase):
    def test_refusal_must_match_request_session_and_kind(self):
        for mismatch in ('session', 'kind'):
            state = {'status': 'sdk_session_ready', 'auth_success_callback': True,
                     'session_id': 'same', 'pid': 123, 'package': lab.LAB_PACKAGE}
            def adb(settings, serial, *args, **kwargs):
                if 'input_bytes' in kwargs:
                    cmd = json.loads(kwargs['input_bytes'])
                    state['rejected_command'] = {'id': cmd['command_id'], 'status': 'failed',
                        'session_id': 'old' if mismatch == 'session' else 'same',
                        'kind': 'record-start' if mismatch == 'kind' else cmd['kind'],
                        'reason': 'stale_session'}
                    raw = b''
                else:
                    raw = b'123' if 'pidof' in args else json.dumps(state).encode()
                return subprocess.CompletedProcess(args, 0, raw, b'')
            with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                    patch.object(lab, 'adb_result', side_effect=adb):
                result = session.send({}, 'phone', 'status', package=lab.LAB_PACKAGE, timeout=.01)
            self.assertEqual(result['status'], 'delivery_unconfirmed')

    def test_lab_roundtrip_never_targets_daily_package(self):
        state = {'status': 'sdk_session_ready', 'auth_success_callback': True,
                 'session_id': 'same', 'pid': 123, 'package': lab.LAB_PACKAGE}
        calls = []
        def adb(settings, serial, *args, **kwargs):
            calls.append(args)
            self.assertNotIn(lab.PACKAGE, args)
            self.assertIn(lab.LAB_PACKAGE, args)
            if 'input_bytes' in kwargs:
                command = json.loads(kwargs['input_bytes'])
                state['last_command'] = {'id': command['command_id'], 'status': 'completed'}
                raw = b''
            else:
                raw = b'123' if 'pidof' in args else json.dumps(state).encode()
            return subprocess.CompletedProcess(args, 0, raw, b'')
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(lab, 'adb_result', side_effect=adb):
            result = session.send({}, 'phone', 'status', session_id='same', package=lab.LAB_PACKAGE)
        self.assertEqual(result['status'], 'completed')
        self.assertEqual(result['package'], lab.LAB_PACKAGE)
        self.assertFalse(any('install' in c or 'am' in c for c in calls))

    def test_busy_rejection_preserves_active_recording_and_matches_command(self):
        for same_id in (True, False):
            state = {'status': 'sdk_session_ready', 'auth_success_callback': True,
                     'session_id': 'same', 'pid': 123, 'package': lab.LAB_PACKAGE,
                     'last_command': {'id': 'active-recording', 'status': 'completed'},
                     'recording': {'id': 'recording-owner', 'phase': 'recording'}}
            def adb(settings, serial, *args, **kwargs):
                if 'input_bytes' in kwargs:
                    cmd = json.loads(kwargs['input_bytes'])
                    state['rejected_command'] = {'id': cmd['command_id'] if same_id else 'old-id',
                        'status': 'failed', 'reason': 'recording_busy'}
                    raw = b''
                else:
                    raw = b'123' if 'pidof' in args else json.dumps(state).encode()
                return subprocess.CompletedProcess(args, 0, raw, b'')
            with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                    patch.object(lab, 'adb_result', side_effect=adb):
                result = session.send({}, 'phone', 'record-start', package=lab.LAB_PACKAGE, timeout=.01)
            self.assertEqual(result['status'], 'failed' if same_id else 'delivery_unconfirmed')
            if same_id:
                self.assertEqual(result['reason'], 'recording_busy')
                self.assertTrue(result['connection_kept'])
                self.assertEqual(result['probe_result']['last_command']['id'], 'active-recording')
                self.assertEqual(result['probe_result']['recording']['phase'], 'recording')

    def test_lab_rejects_foreign_result_identity_before_write(self):
        state = {'status': 'sdk_session_ready', 'auth_success_callback': True,
                 'session_id': 'same', 'pid': 123, 'package': lab.PACKAGE}
        def adb(settings, serial, *args, **kwargs):
            self.assertNotIn('input_bytes', kwargs)
            raw = b'123' if 'pidof' in args else json.dumps(state).encode()
            return subprocess.CompletedProcess(args, 0, raw, b'')
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(lab, 'adb_result', side_effect=adb):
            with self.assertRaises(RuntimeError):
                session.send({}, 'phone', 'record-start', package=lab.LAB_PACKAGE)

    def test_unknown_package_is_rejected_without_adb(self):
        with patch.object(lab, 'adb_result') as adb:
            with self.assertRaises(ValueError):
                session.send({}, 'phone', 'record-start', package='com.rayneo.venus.pub')
            adb.assert_not_called()

    def test_lab_start_never_falls_back_to_daily_launcher(self):
        with patch('sys.argv', ['session.py', 'start', '--serial', 'phone', '--profile', 'sdk-lab']), \
                patch.object(lab, 'settings', return_value={}), patch.object(lab, 'run_device') as launch, \
                patch.object(lab, 'adb_result') as adb, patch('builtins.print'):
            self.assertEqual(session.main(), 2)
            launch.assert_not_called()
            adb.assert_not_called()

    def test_stale_session_never_writes(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(session, 'snapshot', return_value={'live': True, 'result': {'session_id': 'new'}}), \
                patch.object(lab, 'adb_result') as adb:
            with self.assertRaisesRegex(RuntimeError, '已更换'):
                session.send({}, 'phone', 'notify', {'title': 'a', 'content': 'b'}, 'old')
            adb.assert_not_called()

    def test_command_roundtrip_keeps_connection_no_install_or_stop(self):
        state = {'status': 'sdk_session_ready', 'auth_success_callback': True, 'session_id': 'same', 'pid': 123}
        calls = []
        def adb(settings, serial, *args, **kwargs):
            calls.append(args)
            if 'input_bytes' in kwargs:
                command = json.loads(kwargs['input_bytes'])
                self.assertEqual(command['notification']['content'], '`literal` $(literal)\n中文')
                state['last_command'] = {'id': command['command_id'], 'status': 'completed'}
                raw = b''
            elif 'pidof' in args:
                raw = b'123'
            else:
                raw = json.dumps(state).encode()
            return subprocess.CompletedProcess(args, 0, raw, b'')
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(lab, 'adb_result', side_effect=adb):
            result = session.send({}, 'phone', 'notify', {'title': 'a', 'content': '`literal` $(literal)\n中文'}, 'same')
        self.assertEqual(result['status'], 'completed')
        self.assertTrue(result['connection_kept'])
        self.assertFalse(any('install' in c or 'am' in c for c in calls))

    def test_pid_mismatch_is_not_live(self):
        result = {'status': 'sdk_session_ready', 'pid': 12, 'auth_success_callback': True}
        with patch.object(lab, 'adb_result', side_effect=[
                subprocess.CompletedProcess([], 0, json.dumps(result).encode(), b''),
                subprocess.CompletedProcess([], 0, b'13', b'')]):
            self.assertFalse(session.snapshot({}, 'phone')['live'])
        self.assertFalse(display_observer.present(result, '13')['live'])
        self.assertTrue(display_observer.present(result, '12')['live'])

    @patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=True)
    def test_legacy_session_launcher_leaves_ready_process_running(self, _legacy):
        calls = []
        state = {'status': 'sdk_session_ready', 'pid': 123, 'stages': [{}]}
        def adb(settings, serial, *args, **kwargs):
            calls.append(args)
            raw = b''
            if args == ('get-state',): raw = b'device'
            elif 'getprop' in args: raw = b'36'
            elif args[0] == 'install': raw = b'Success'
            elif 'pidof' in args: raw = b'123'
            elif 'cat' in args:
                raw = json.dumps(state if any('start' in c or 'start-foreground-service' in c for c in calls) else {}).encode()
            return subprocess.CompletedProcess(args, 0, raw, b'')
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(lab, 'latest_output', return_value=Path(temp)), patch.object(lab, 'verify'), \
                patch.object(lab, 'digest', return_value='hash'), patch.object(lab, 'prepare_observation_permissions', return_value={}), \
                patch.object(lab, 'adb_result', side_effect=adb):
            result = lab.run_device({}, 'phone', True, target_address='00:11:22:33:44:55', sdk_mode='sdk-session', pairing_ready=True)
        self.assertTrue(result['session_kept_running'])
        self.assertEqual(sum('force-stop' in c for c in calls), 1)  # only before launch

    @patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=True)
    def test_legacy_active_session_prevents_reinstall(self, _legacy):
        calls = []
        def adb(settings, serial, *args, **kwargs):
            calls.append(args)
            raw = (b'device' if args == ('get-state',) else b'36' if 'getprop' in args
                   else b'123' if 'pidof' in args else b'{"status":"sdk_session_ready","pid":123}')
            return subprocess.CompletedProcess(args, 0, raw, b'')
        with tempfile.TemporaryDirectory() as temp, patch.object(lab, 'ROOT', Path(temp)), \
                patch.object(lab, 'latest_output', return_value=Path(temp)), patch.object(lab, 'verify'), \
                patch.object(lab, 'digest', return_value='hash'), patch.object(lab, 'adb_result', side_effect=adb):
            result = lab.run_device({}, 'phone', True)
        self.assertEqual(result['status'], 'blocked_or_failed')
        self.assertFalse(any('install' in c or 'force-stop' in c for c in calls))


if __name__ == '__main__':
    unittest.main()
