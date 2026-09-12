import contextlib
import io
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
import lab
import model_probe as model


class ModelChecks(unittest.TestCase):
    def test_config_blocks_credentials_in_url_and_remote_http(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'llm.json'
            for base in ('http://example.com/v1', 'https://key@example.com', 'https://example.com?api_key=value'):
                lab.write_json(path, {'base_url': base, 'model': 'test', 'api_key': 'fake-for-test'})
                with self.assertRaises(model.ProbeError): model.load_config(path)
            lab.write_json(path, {'base_url': 'https://example.com/v1', 'model': 'test', 'api_key': 'fake-for-test'})
            self.assertEqual(model.load_config(path)['endpoint'], 'https://example.com/v1/chat/completions')

    def test_incomplete_tool_and_empty_output_are_not_displayable(self):
        for choice in (
            {'finish_reason': 'length', 'message': {'content': 'partial'}},
            {'finish_reason': 'tool_calls', 'message': {'content': '', 'tool_calls': [{'id': 'x'}]}},
            {'finish_reason': 'stop', 'message': {'content': ''}},
            {'finish_reason': 'stop', 'message': {'content': '长' * 501}},
        ):
            with self.assertRaises(model.ProbeError): model.parse_answer(json.dumps({'choices': [choice]}))

    def test_real_local_http_request_and_redirect_refusal(self):
        events = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                events.append((self.path, body, self.headers.get('Authorization')))
                if self.path == '/redirect':
                    self.send_response(307); self.send_header('Location', '/leak'); self.end_headers(); return
                answer = {'choices': [{'finish_reason': 'stop', 'message': {'content': '本地模拟响应，不是真实模型回答。'}}],
                          'usage': {'total_tokens': 12, 'private_field': 'omit'}}
                raw = json.dumps(answer).encode()
                self.send_response(200); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        config = {'endpoint': f'http://127.0.0.1:{server.server_port}/chat/completions', 'api_key': 'fake-for-test', 'model': 'test', 'max_tokens': 128, 'thinking': 'disabled'}
        try:
            answer, usage = model.ask(config, '本地测试')
            self.assertIn('模拟', answer); self.assertEqual(usage, {'total_tokens': 12})
            self.assertFalse(events[0][1]['stream'])
            self.assertEqual(events[0][1]['thinking'], {'type': 'disabled'})
            self.assertNotIn('tools', events[0][1])
            self.assertEqual(events[0][2], 'Bearer fake-for-test')
            with self.assertRaises(model.ProbeError) as error:
                model.ask({**config, 'endpoint': f'http://127.0.0.1:{server.server_port}/redirect'}, 'redirect')
            self.assertNotIn('fake-for-test', str(error.exception))
            self.assertEqual([e[0] for e in events], ['/chat/completions', '/redirect'])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)

    def test_default_config_check_never_calls_model_or_device(self):
        config = {'endpoint': 'https://example.com/chat/completions', 'model': 'test', 'api_key': 'fake-for-test', 'max_tokens': 128}
        with patch('sys.argv', ['model_probe.py']), patch.object(model, 'load_config', return_value=config), \
                patch.object(model, 'ask') as ask, patch.object(lab, 'run_device') as device, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(model.main(), 0)
            ask.assert_not_called(); device.assert_not_called()
            self.assertNotIn('fake-for-test', output.getvalue())

    def test_notification_file_rejects_other_data_and_preserves_literal_text(self):
        value = {'title': '标题', 'content': 'literal $(x) `cmd` "quote"\n中文'}
        self.assertEqual(lab.validate_notification(value), value)
        for invalid in ({**value, 'api_key': 'never-send'}, {'title': '', 'content': 'x'}, {'title': 'x', 'content': 'bad\x00'}):
            with self.assertRaises(ValueError): lab.validate_notification(invalid)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'notification.json'; lab.write_json(path, {**value, 'api_key': 'never-send'})
            with patch.object(lab, 'adb_result') as adb:
                with self.assertRaises(ValueError):
                    lab.run_device({}, 'phone', execute=True, target_address='00:11:22:33:44:55', sdk_mode='sdk-text', notification_file=path)
                adb.assert_not_called()
