"""Reuse one authenticated foreground BLE session; commands never reinstall the app."""
import argparse
import hashlib
import json
import os
import time
import uuid
from pathlib import Path

import lab


def snapshot(settings, serial):
    raw = lab.adb_result(settings, serial, 'shell', 'run-as', lab.PACKAGE,
                         'cat', 'files/result.json', check=False)
    pid = lab.adb_result(settings, serial, 'shell', 'pidof', lab.PACKAGE, check=False)
    try:
        result = json.loads(raw.stdout) if raw.returncode == 0 and len(raw.stdout) <= 262144 else {}
    except ValueError:
        result = {}
    if not isinstance(result, dict):
        result = {}
    live = (pid.returncode == 0 and result.get('status') == 'sdk_session_ready'
            and result.get('auth_success_callback') is True
            and str(result.get('pid')).encode() == pid.stdout.strip())
    return {'live': live, 'result': result}


def require_live(settings, serial, session_id=None):
    state = snapshot(settings, serial)
    if not state['live']:
        raise RuntimeError('没有正在运行的持续会话；请先启动一次 session')
    if session_id is not None and state['result'].get('session_id') != session_id:
        raise RuntimeError('连接会话已更换；本条消息未发送')
    return state['result']


def send(settings, serial, kind, notification=None, session_id=None, timeout=12):
    if kind not in ('notify', 'status', 'stop', 'pair', 'voice', 'wake', 'spp', 'audio', 'codec', 'voice-cloud', 'voice-standby', 'standby-off', 'spp-off', 'voice-ble', 'voice-native', 'record-start', 'record-stop'):
        raise ValueError('Unknown command')
    if kind == 'notify':
        lab.validate_notification(notification)
    lock_dir = lab.ROOT / 'out/session-locks'
    lock_dir.mkdir(parents=True, exist_ok=True)
    lock = lock_dir / (hashlib.sha256(serial.encode()).hexdigest() + '.lock')
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise RuntimeError('本机已有会话命令在执行，请等待完成') from None
    os.close(fd)
    try:
        current = require_live(settings, serial, session_id)
        if current.get('last_command', {}).get('status') == 'pending' and kind not in ('stop', 'standby-off', 'record-stop'):
            raise RuntimeError('眼镜上一条命令仍待完成，未重复发送；可查询状态或停止会话')
        command = {'session_id': current['session_id'], 'command_id': str(uuid.uuid4()), 'kind': kind}
        if kind == 'notify':
            command['notification'] = notification
        raw = json.dumps(command, ensure_ascii=False).encode('utf-8')
        if len(raw) > 8192:
            raise ValueError('Command exceeds input limit')
        # Fixed shell program, payload via stdin; atomically publish only complete JSON.
        lab.adb_result(settings, serial, 'shell', '-T', 'run-as', lab.PACKAGE, 'sh', '-c',
            "'cat > files/session-command.tmp && mv files/session-command.tmp files/session-command.json'",
            input_bytes=raw)
        deadline = time.monotonic() + timeout
        report = {'status': 'delivery_unconfirmed', 'command_id': command['command_id'],
                  'session_id': command['session_id'], 'kind': kind, 'lens_verified': False}
        while time.monotonic() < deadline:
            state = snapshot(settings, serial)
            result = state['result']
            if result.get('session_id') != command['session_id']:
                report['reason'] = 'Session changed while waiting; no retry'
                break
            latest = result.get('last_command', {})
            if latest.get('id') == command['command_id'] and latest.get('status') in ('completed', 'failed'):
                report.update(status=latest['status'], probe_result=result, connection_kept=state['live'])
                break
            if not state['live']:
                report.update(reason='Session ended before command completion', probe_result=result)
                break
            time.sleep(.4)
        output = lab.fresh_output_dir('session-events') / 'command.json'
        lab.write_json(output, report)
        report['result_file'] = str(output)
        return report
    finally:
        lock.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'status', 'query', 'send', 'stop', 'pair', 'voice', 'wake', 'spp', 'audio', 'codec', 'voice-cloud', 'voice-standby', 'standby-off', 'spp-off', 'voice-ble', 'voice-native', 'record-start', 'record-stop'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--address')
    parser.add_argument('--pairing-ready', action='store_true')
    parser.add_argument('--seconds', type=int, default=1800)
    parser.add_argument('--notification-file', type=Path)
    parser.add_argument('--session-id')
    args = parser.parse_args()
    try:
        settings = lab.settings()
        if args.action == 'status':
            result = snapshot(settings, args.serial)
        elif args.action == 'start':
            current = snapshot(settings, args.serial)
            if current['live']:
                if args.address and current['result'].get('target_address') != args.address.upper():
                    raise RuntimeError('已有另一目标的持续会话')
                result = {'status': 'sdk_session_ready', 'reused': True, 'probe_result': current['result']}
            else:
                if not args.address:
                    raise ValueError('启动需要 --address；首次连接另需 --pairing-ready')
                result = lab.run_device(settings, args.serial, execute=True, target_address=args.address,
                    sdk_mode='sdk-session', pairing_ready=args.pairing_ready, session_seconds=args.seconds)
        else:
            notification = lab.read_notification(args.notification_file) if args.action == 'send' and args.notification_file else None
            result = send(settings, args.serial, {'query': 'status', 'send': 'notify', 'stop': 'stop', 'pair': 'pair', 'voice': 'voice', 'wake': 'wake', 'spp': 'spp', 'audio': 'audio', 'codec': 'codec', 'voice-cloud': 'voice-cloud', 'voice-standby': 'voice-standby', 'standby-off': 'standby-off', 'spp-off': 'spp-off', 'voice-ble': 'voice-ble', 'voice-native': 'voice-native', 'record-start': 'record-start', 'record-stop': 'record-stop'}[args.action],
                          notification, args.session_id, timeout=180 if args.action == 'record-stop' else 40 if args.action == 'record-start' else 60 if args.action == 'pair' else 28 if args.action == 'spp' else 15 if args.action == 'audio' else 12)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2 if result.get('status') in ('failed', 'blocked_or_failed', 'delivery_unconfirmed') else 0
    except (RuntimeError, ValueError, OSError) as error:
        print(json.dumps({'status': 'blocked_or_failed', 'error': str(error)}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
