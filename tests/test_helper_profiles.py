"""Exercise public helper entry points without devices, servers or provider calls."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import display_observer
import idle_check
import lab
import model_probe


PROFILES = (([], lab.LAB_PACKAGE), (['--profile', 'sdk-lab'], lab.LAB_PACKAGE),
            (['--profile', 'daily'], lab.PACKAGE))


class HelperProfileChecks(unittest.TestCase):
    def test_observer_reads_result_and_pid_from_selected_package(self):
        for flags, package in PROFILES:
            with self.subTest(flags=flags):
                stop = MagicMock()
                stop.is_set.side_effect = [False, True]
                calls = []

                def adb(argv, **kwargs):
                    calls.append(argv)
                    body = b'42' if 'pidof' in argv else json.dumps({
                        'pid': 42, 'status': 'sdk_session_ready'}).encode()
                    return subprocess.CompletedProcess(argv, 0, body, b'')

                def thread(**kwargs):
                    value = MagicMock()
                    value.start.side_effect = kwargs['target']
                    return value

                with patch('sys.argv', ['display_observer.py', '--serial', 'test-phone', *flags]), \
                        patch.object(lab, 'settings', return_value={'adb': 'mock-adb'}), \
                        patch.object(display_observer.subprocess, 'run', side_effect=adb), \
                        patch.object(display_observer.threading, 'Event', return_value=stop), \
                        patch.object(display_observer.threading, 'Thread', side_effect=thread), \
                        patch.object(display_observer.threading, 'Timer'), \
                        patch.object(display_observer, 'ThreadingHTTPServer') as server, \
                        contextlib.redirect_stdout(io.StringIO()):
                    display_observer.main()
                self.assertEqual(calls, [
                    ['mock-adb', '-s', 'test-phone', 'shell', 'run-as', package, 'cat', 'files/result.json'],
                    ['mock-adb', '-s', 'test-phone', 'shell', 'pidof', package]])
                server.assert_called_once()

    def test_idle_check_reads_both_snapshots_from_selected_package(self):
        for flags, package in PROFILES:
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as tmp:
                state = {'live': True, 'result': {'session_id': 'same', 'pid': 42,
                         'standby': {'ready': True, 'enabled': True}}}
                with patch('sys.argv', ['idle_check.py', '--serial', 'test-phone', *flags]), \
                        patch.object(lab, 'settings', return_value={}), \
                        patch.object(idle_check.session, 'snapshot', return_value=state) as snapshot, \
                        patch.object(idle_check.time, 'sleep'), \
                        patch.object(lab, 'fresh_output_dir', return_value=Path(tmp)), \
                        contextlib.redirect_stdout(io.StringIO()):
                    idle_check.main()
                self.assertEqual(snapshot.call_count, 2)
                for call in snapshot.call_args_list:
                    self.assertEqual(call.args, ({}, 'test-phone'))
                    self.assertEqual(call.kwargs, {'package': package})
                report = json.loads((Path(tmp) / 'idle-check.json').read_text(encoding='utf-8'))
                self.assertEqual(report['check']['status'], 'passed')

    def test_model_session_preflight_and_delivery_use_same_selected_package(self):
        config = {'endpoint': 'https://example.invalid/chat/completions', 'model': 'mock',
                  'max_tokens': 128, 'api_key': 'synthetic-test-key'}
        for flags, package in PROFILES:
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as tmp:
                with patch('sys.argv', ['model_probe.py', '--execute', '--send-to-glasses',
                                       '--use-session', '--serial', 'test-phone', *flags]), \
                        patch.object(model_probe, 'load_config', return_value=config), \
                        patch.object(model_probe, 'ask', return_value=('mock answer', {})), \
                        patch.object(lab, 'settings', return_value={}), \
                        patch.object(lab, 'fresh_output_dir', return_value=Path(tmp)), \
                        patch.object(model_probe.session, 'require_live', return_value={'session_id': 'same'}) as live, \
                        patch.object(model_probe.session, 'send', return_value={
                            'status': 'completed', 'result_file': 'mock-result.json'}) as send, \
                        patch.object(lab, 'run_device') as device, \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(model_probe.main(), 0)
                live.assert_called_once_with({}, 'test-phone', package=package)
                send.assert_called_once_with({}, 'test-phone', 'notify',
                    {'title': '模型回答', 'content': 'mock answer'}, 'same', package=package)
                device.assert_not_called()

    def test_disabled_legacy_delivery_is_rejected_before_model_request(self):
        config = {'endpoint': 'https://example.invalid/chat/completions', 'model': 'mock', 'max_tokens': 128}
        with patch('sys.argv', ['model_probe.py', '--execute', '--send-to-glasses',
                               '--serial', 'test-phone', '--address', '00:11:22:33:44:55', '--pairing-ready']), \
                patch.object(model_probe, 'load_config', return_value=config), \
                patch.object(lab, 'legacy_diagnostic_launch_enabled', return_value=False), \
                patch.object(model_probe, 'ask') as ask, \
                patch.object(model_probe.urllib.request, 'urlopen') as urlopen, \
                patch.object(model_probe.urllib.request, 'build_opener') as opener, \
                patch.object(lab, 'run_device') as device, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(model_probe.main(), 2)
        ask.assert_not_called()
        urlopen.assert_not_called()
        opener.assert_not_called()
        device.assert_not_called()
