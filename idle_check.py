"""Two-snapshot idle protocol check; not a battery-life measurement and sends no BLE command."""
import argparse
import time
import lab
import session

METRICS = ('recorder_start_requests_total', 'audio_receive_packets_total',
           'idle_audio_packets', 'cloud_upload_requests_total', 'business_submit_total', 'cloud_stream_connections_total')

def compare(before, after, elapsed):
    a, b = before['result'], after['result']
    same = before['live'] and after['live'] and a.get('session_id') == b.get('session_id') and a.get('pid') == b.get('pid')
    idle = all(r.get('standby', {}).get('ready') and r.get('standby', {}).get('enabled') and not r.get('asr_stream_active', False) for r in (a, b))
    delta = {k: b.get(k, 0) - a.get(k, 0) for k in METRICS}
    return {'status': 'passed' if same and idle and all(v == 0 for v in delta.values()) else 'not_quiet_or_session_changed',
            'session_id': b.get('session_id'), 'elapsed_seconds': elapsed, 'same_live_session': bool(same),
            'delta': delta, 'battery_life_measured': False,
            'scope': 'Own app recorder requests, received voice packets, cloud upload requests and business submissions; excludes vendor SDK internal heartbeat'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--seconds', type=int, default=60, choices=range(10, 1801), metavar='10..1800')
    args = parser.parse_args()
    settings = lab.settings(); start = session.snapshot(settings, args.serial)
    now = time.monotonic(); print('Idle check running; no Bluetooth commands are sent.', flush=True)
    time.sleep(args.seconds)
    finish = session.snapshot(settings, args.serial)
    result = compare(start, finish, round(time.monotonic() - now, 3))
    target = lab.fresh_output_dir('power-tests') / 'idle-check.json'
    lab.write_json(target, {'check': result, 'before': start, 'after': finish})
    print(result, flush=True); print(target, flush=True)

if __name__ == '__main__':
    main()
