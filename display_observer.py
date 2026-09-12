"""Read-only USB observer for our own Android probe. No Bluetooth or write commands."""
import argparse
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import lab

ASSETS = Path(__file__).parent / 'observer'
SAFE_KEYS = ('status', 'mode', 'session_id', 'pid', 'sampled_at_ms', 'auth_success_callback',
             'status_reply_received', 'screen_raw', 'reported_page', 'submitted_text',
             'text_send_completed', 'reported_status', 'notification_reply', 'reason', 'stages', 'last_command', 'session_seconds',
             'system_bond_state', 'own_saved_target_count', 'sdk_bond_success', 'reconnect_from_saved', 'voice_test',
             'voice_wakeup_request', 'reported_ai_settings', 'last_spp_state', 'spp_auth_success_callback',
             'channel_observer_packets', 'voice_receive_route', 'last_send_route', 'codec_test', 'glasses_cloud', 'standby',
             'submitted_transcript', 'audio_receive_packets_total', 'idle_audio_packets', 'business_submit_total',
             'recorder_start_requests_total', 'cloud_upload_requests_total', 'submitted_transcript_final',
             'cloud_stream_connections_total', 'asr_stream_active', 'connection_service_running')


def present(result, pid, available=True, confirmation=None):
    live = available and result.get('status') in ('running', 'sdk_session_ready') and str(result.get('pid')) == pid.strip() and bool(pid.strip())
    safe = {key: result[key] for key in SAFE_KEYS if key in result}
    if (confirmation and confirmation.get('session_id') == result.get('session_id')
            and confirmation.get('notification_uid') == result.get('submitted_text', {}).get('uid')
            and confirmation.get('status') == 'confirmed_complete' and confirmation.get('source') == 'user'):
        safe['visual_confirmation'] = {'status': 'confirmed_complete', 'source': 'user',
                                       'statement': '看到了，文字完整'}
    return {'transport': 'online' if available else 'offline', 'live': live,
            'result': safe,
            'notice': '协议状态重绘；不是镜片截图。发送完成不等于镜片已显示。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--port', type=int, default=8791)
    parser.add_argument('--seconds', type=int, default=1800)
    args = parser.parse_args()
    adb = str(lab.settings()['adb'])
    state = {'transport': 'waiting', 'live': False, 'result': {}}
    lock = threading.Lock()
    stop = threading.Event()

    def run(*words):
        proc = subprocess.run([adb, '-s', args.serial, *words], capture_output=True, timeout=3,
                              creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return proc.returncode, proc.stdout

    def poll():
        previous = {}
        confirmation = None
        while not stop.is_set():
            try:
                code, raw = run('shell', 'run-as', lab.PACKAGE, 'cat', 'files/result.json')
                if code or len(raw) > 262144:
                    raise ValueError('No bounded result')
                result = json.loads(raw)
                code, raw_pid = run('shell', 'pidof', lab.PACKAGE)
                if code not in (0, 1):
                    raise ValueError('PID unavailable')
                previous = result
                session = result.get('session_id', '')
                confirmation = None
                if re.fullmatch(r'[0-9a-f-]{36}', session):
                    path = lab.ROOT / 'out/visual-confirmations' / (session + '.json')
                    if path.is_file():
                        confirmation = json.loads(path.read_text(encoding='utf-8'))
                current = present(result, raw_pid.decode('ascii').strip(), confirmation=confirmation)
            except (ValueError, OSError, subprocess.TimeoutExpired):
                current = present(previous, '', available=False, confirmation=confirmation)
            with lock:
                state.clear(); state.update(current)
            stop.wait(1)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.headers.get('Host') != f'127.0.0.1:{args.port}':
                self.send_error(403); return
            files = {'/': ('index.html', 'text/html'), '/app.js': ('app.js', 'text/javascript'),
                     '/style.css': ('style.css', 'text/css')}
            if self.path == '/api/state':
                with lock:
                    body = json.dumps(state, ensure_ascii=False).encode('utf-8')
                kind = 'application/json'
            elif self.path in files:
                name, kind = files[self.path]
                body = (ASSETS / name).read_bytes()
            else:
                self.send_error(404); return
            self.send_response(200)
            self.send_header('Content-Type', kind + '; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers(); self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    threading.Thread(target=poll, daemon=True).start()
    timer = threading.Timer(args.seconds, server.shutdown); timer.daemon = True; timer.start()
    print(f'Observer http://127.0.0.1:{args.port}/; read-only; lifetime {args.seconds}s', flush=True)
    try:
        server.serve_forever()
    finally:
        stop.set(); timer.cancel(); server.server_close()


if __name__ == '__main__':
    main()
