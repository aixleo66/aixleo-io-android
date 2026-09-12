"""RayNeo local experiment CLI. Python 3.10+; build/run use stdlib only."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import unicodedata
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PACKAGE = 'dev.xr.rayneo.probe'
SAMPLE_HASH = '32c67abbdf3c87eb113f965f984b79eee17b4bf3136f14f535375752ab1a7093'
PAYLOAD_HASH = 'd1d54046ed811e0bfb965f30a051a5bd4f3342760c3c69cc3aaca32424860f9f'
PAYLOAD_ENTRIES = [
    'classes.dex', 'classes2.dex', 'classes3.dex',
    'META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory',
    'META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler',
]


def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def vendor_input_kind(path):
    """Accept only the pinned original APK or its byte-identical research payload."""
    path = Path(path)
    if not path.is_file():
        return None
    actual = digest(path)
    if actual == SAMPLE_HASH:
        return 'official_apk'
    if actual == PAYLOAD_HASH:
        with zipfile.ZipFile(path) as z:
            if len(z.namelist()) == len(PAYLOAD_ENTRIES) and set(z.namelist()) == set(PAYLOAD_ENTRIES):
                return 'pinned_vendor_payload'
    return None


def write_json(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def fresh_output_dir(category):
    parent = ROOT / 'out' / category
    parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    # Windows clock resolution can repeat timestamps across adjacent runs.
    return Path(tempfile.mkdtemp(prefix=stamp + '-', dir=parent))


def command(args, check=True, timeout=120, input_bytes=None):
    r = subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout, input=input_bytes)
    if check and r.returncode:
        # No credential arguments are used by this local debug-only workflow.
        raise RuntimeError(f'command exited {r.returncode}: {Path(str(args[0])).name}\n'
                           + r.stdout.decode('utf-8', errors='replace')
                           + r.stderr.decode('utf-8', errors='replace'))
    return r


def path_value(value):
    p = Path(os.path.expandvars(os.path.expanduser(value)))
    return p.resolve() if p.is_absolute() else (ROOT / p).resolve()


def settings(config=None):
    config = Path(config) if config else ROOT / 'config.local.json'
    c = read_json(config) if config.exists() else {}
    exe = '.exe' if os.name == 'nt' else ''
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    sdk_root = Path(sdk) if sdk else None
    java_home = c.get('java_home') or os.environ.get('JAVA_HOME')
    if not java_home and (ROOT / '.tools/jdk/jdk-21.0.12.1+1').exists():
        java_home = str(ROOT / '.tools/jdk/jdk-21.0.12.1+1')
    if not java_home and shutil.which('javac'):
        java_home = str(Path(shutil.which('javac')).resolve().parent.parent)
    bt = c.get('build_tools') or str(sdk_root / 'build-tools/36.0.0' if sdk_root else ROOT / '.tools/build-tools/android-16')
    jar = c.get('android_jar') or str(sdk_root / 'platforms/android-36/android.jar' if sdk_root else ROOT / '.tools/platform/android-36-ext19/android.jar')
    adb = c.get('adb') or (str(sdk_root / ('platform-tools/adb' + exe)) if sdk_root else None)
    adb = adb or (str(ROOT / ('.tools/platform-tools/platform-tools/adb' + exe))
                  if (ROOT / ('.tools/platform-tools/platform-tools/adb' + exe)).exists() else shutil.which('adb'))
    return {
        'sample': path_value(c.get('sample') or ('vendor/rayneo-venus-1.0.2-68/vendor-payload.jar'
            if (ROOT / 'vendor/rayneo-venus-1.0.2-68/vendor-payload.jar').is_file()
            else 'private/samples/rayneo-venus-1.0.2-68.apk')),
        'key': path_value(c.get('key') or 'private/keys/local-debug.p12'),
        'java_home': path_value(java_home) if java_home else None,
        'build_tools': path_value(bt), 'android_jar': path_value(jar),
        'adb': path_value(adb) if adb else None, 'exe': exe,
    }


def tool(s, name):
    if not s['java_home']:
        raise RuntimeError('找不到 JDK；先 bootstrap 或配置 java_home / JAVA_HOME')
    return s['java_home'] / 'bin' / (name + s['exe'])


def doctor(s):
    required = {'official_apk': s['sample'], 'debug_key': s['key'], 'android_jar': s['android_jar'],
                'adb': s['adb'], 'aapt2': s['build_tools'] / ('aapt2' + s['exe']),
                'zipalign': s['build_tools'] / ('zipalign' + s['exe']),
                'd8': s['build_tools'] / 'lib/d8.jar', 'apksigner': s['build_tools'] / 'lib/apksigner.jar'}
    for n in ['java', 'javac', 'keytool']:
        required[n] = tool(s, n) if s['java_home'] else None
    checks = {name: {'path': str(p) if p else None, 'exists': bool(p and p.is_file())} for name, p in required.items()}
    kind = vendor_input_kind(s['sample'])
    sample_ok = kind is not None
    report = {'python': platform.python_version(), 'os': platform.platform(), 'checks': checks,
              'sample_sha256_matches': kind == 'official_apk', 'vendor_input_kind': kind,
              'vendor_input_verified': sample_ok,
              'build_ready': sample_ok and all(v['exists'] for k, v in checks.items() if k != 'adb'),
              'adb_ready': checks['adb']['exists'], 'device_tested': False}
    write_json(ROOT / 'out/doctor.json', report)
    return report


def safe_extract(archive, target):
    target = Path(target).resolve()
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if not (target / name).resolve().is_relative_to(target):
                raise RuntimeError('archive contains unsafe path')
        z.extractall(target)


def bootstrap(offline=False):
    if os.name != 'nt' or platform.machine().lower() not in ('amd64', 'x86_64'):
        raise RuntimeError('自动安装包仅固定了 Windows x64；其他系统请配置本机 JDK/Android SDK，尚未实测')
    lock = read_json(ROOT / 'toolchain.lock.json')
    cache = ROOT / 'private/downloads'
    cache.mkdir(parents=True, exist_ok=True)
    for item in lock['windows_x64']:
        archive = cache / item['archive']
        if not archive.exists():
            if offline:
                raise RuntimeError(f'离线安装缺少 {archive.name}；请同步 private/downloads')
            partial = archive.with_suffix('.download')
            with urllib.request.urlopen(item['url'], timeout=60) as response, partial.open('wb') as out:
                shutil.copyfileobj(response, out)
            if digest(partial, item['algorithm']) != item['checksum']:
                raise RuntimeError(f'下载校验失败: {item["archive"]}')
            partial.replace(archive)
        if (archive.stat().st_size != item['size'] or digest(archive, item['algorithm']) != item['checksum']
                or digest(archive) != item['archive_sha256']):
            raise RuntimeError(f'缓存校验失败: {archive.name}；不加载该文件')
        target = ROOT / '.tools' / item['destination']
        if not (target / item['sentinel']).is_file():
            print('Extracting ' + item['archive'], flush=True)
            safe_extract(archive, target)
    return {'status': 'tools_prepared', 'note': '工具已准备；研究候选包可用随包 payload，请配置自己的本地调试签名。private-check 仅用于原私有迁移环境'}


def check_private():
    base = ROOT / 'private'
    manifest = read_json(base / 'manifest.json')
    failures = []
    for entry in manifest['files']:
        p = (base / entry['path']).resolve()
        if not p.is_relative_to(base.resolve()):
            raise RuntimeError('私有清单路径越界')
        if not p.is_file() or digest(p) != entry['sha256']:
            failures.append(entry['path'])
    return {'status': 'private_verified' if not failures else 'blocked_or_failed',
            'files_checked': len(manifest['files']), 'missing_or_changed': failures}


def build_workspace():
    base = Path(os.environ.get('RAYNEO_BUILD_TEMP', tempfile.gettempdir())).resolve()
    if os.name == 'nt' and not str(base).isascii():
        raise RuntimeError('Windows 打包工具需要英文临时路径；请将 RAYNEO_BUILD_TEMP 指向如 C:/Temp')
    base.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(prefix='rayneo-build-', dir=base)


def verify(s, output):
    output = Path(output)
    bm = read_json(output / 'build-manifest.json')
    apk = output / 'rayneo-init-probe.apk'
    if digest(apk) != bm['apk_sha256']:
        raise RuntimeError('APK 与构建清单指纹不符')
    if not vendor_input_kind(s['sample']):
        raise RuntimeError('厂商输入须为固定官方 APK 或固定 payload，指纹不符')
    with zipfile.ZipFile(apk) as outer, zipfile.ZipFile(s['sample']) as original:
        payload = outer.read('assets/vendor-payload.jar')
        if hashlib.sha256(payload).hexdigest() != bm['payload_sha256']:
            raise RuntimeError('payload 指纹不符')
        with zipfile.ZipFile(io.BytesIO(payload)) as inner:
            if set(inner.namelist()) != set(PAYLOAD_ENTRIES):
                raise RuntimeError('payload 条目不符')
            for name in PAYLOAD_ENTRIES:
                if inner.read(name) != original.read(name):
                    raise RuntimeError('原始条目字节不符: ' + name)
    # Some Windows Android native tools reject Unicode input paths.
    with build_workspace() as tmp:
        check_apk = Path(tmp) / 'verify.apk'
        shutil.copyfile(apk, check_apk)
        r = command([tool(s, 'java'), '-jar', s['build_tools'] / 'lib/apksigner.jar',
                     'verify', '--verbose', '--print-certs', check_apk])
        alignment = command([s['build_tools'] / ('zipalign' + s['exe']), '-c', '-p', '4', check_apk])
    report = {'apk_sha256': digest(apk), 'original_entries_verified': PAYLOAD_ENTRIES,
              'signature_exit_code': r.returncode, 'alignment_exit_code': alignment.returncode,
              'signature_output': r.stdout.decode('utf-8', errors='replace'), 'device_tested': False}
    write_json(output / 'verification.json', report)
    return report


def build(s):
    readiness = doctor(s)
    if not readiness['build_ready']:
        raise RuntimeError('构建环境不齐；查看 out/doctor.json。请配置自己的本地调试签名；不自动覆盖或创建签名')
    destination = fresh_output_dir('builds')
    # Fresh ASCII workspace avoids stale classes and Windows Unicode path issues.
    with build_workspace() as tmp:
        output = Path(tmp)
        build_in_workspace(s, output)
        shutil.copytree(output, destination, dirs_exist_ok=True)
    verify(s, destination)
    write_json(ROOT / 'out/latest-build.json', {'directory': destination.relative_to(ROOT).as_posix()})
    return {'status': 'build_verified', 'output': str(destination),
            'apk_sha256': digest(destination / 'rayneo-init-probe.apk'), 'device_tested': False}


def build_in_workspace(s, output):
    for name in ['assets', 'classes', 'dex', 'generated']:
        (output / name).mkdir(parents=True)
    payload = output / 'assets/vendor-payload.jar'
    for license_file in (ROOT / 'app/lib').glob('*LICENSE.txt'):
        shutil.copyfile(license_file, output / 'assets' / license_file.name)
    entries = []
    with zipfile.ZipFile(s['sample']) as original, zipfile.ZipFile(payload, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in PAYLOAD_ENTRIES:
            data = original.read(name)
            entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, data)
            entries.append({'entry': name, 'sha256': hashlib.sha256(data).hexdigest()})
    generated = output / 'generated/PayloadInfo.java'
    generated.write_text('package dev.xr.rayneo.probe; final class PayloadInfo { static final String SHA256="'
                         + digest(payload) + '"; }', encoding='utf-8')
    sources = list((ROOT / 'app/src').glob('*.java'))
    libraries = []
    for dep in read_json(ROOT / 'app/lib/manifest.json'):
        path = ROOT / 'app/lib' / dep['file']
        if digest(path) != dep['sha256']:
            raise RuntimeError('Dependency checksum mismatch: ' + dep['file'])
        libraries.append(path)
    command([tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
             '-classpath', os.pathsep.join(map(str, [s['android_jar'], *libraries])), '-d', output / 'classes', *sources, generated])
    command([tool(s, 'java'), '-cp', s['build_tools'] / 'lib/d8.jar', 'com.android.tools.r8.D8',
             '--min-api', '29', '--lib', s['android_jar'], '--output', output / 'dex',
             *list((output / 'classes').rglob('*.class')), *libraries])
    unsigned, aligned, apk = [output / n for n in ['unsigned.apk', 'aligned.apk', 'rayneo-init-probe.apk']]
    shutil.copyfile(ROOT / 'app/AndroidManifest.xml', output / 'AndroidManifest.xml')
    shutil.copyfile(s['android_jar'], output / 'android.jar')
    command([s['build_tools'] / ('aapt2' + s['exe']), 'link', '-o', unsigned,
             '--manifest', output / 'AndroidManifest.xml', '-I', output / 'android.jar', '-A', output / 'assets'])
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(output / 'dex/classes.dex', 'classes.dex')
    command([s['build_tools'] / ('zipalign' + s['exe']), '-f', '-p', '4', unsigned, aligned])
    command([tool(s, 'java'), '-jar', s['build_tools'] / 'lib/apksigner.jar', 'sign',
             '--ks', s['key'], '--ks-pass', 'pass:android', '--out', apk, aligned])
    manifest = {'apk_sha256': digest(apk), 'payload_sha256': digest(payload), 'sample_sha256': SAMPLE_HASH,
                'vendor_input_kind': vendor_input_kind(s['sample']), 'vendor_input_sha256': digest(s['sample']),
                'unmodified_entries': entries, 'source_sha256': {p.relative_to(ROOT).as_posix(): digest(p)
                  for p in [*sources, *libraries, ROOT / 'app/lib/manifest.json', ROOT / 'app/AndroidManifest.xml', ROOT / 'lab.py']},
                'scope': 'initialization-observation-and-gated-sdk-connection', 'native_libraries_included': False,
                'declared_permissions': [p.attrib['{http://schemas.android.com/apk/res/android}name']
                    for p in ET.parse(ROOT / 'app/AndroidManifest.xml').getroot().findall('uses-permission')],
                'device_tested': False}
    write_json(output / 'build-manifest.json', manifest)
    verify(s, output)


def latest_output(value=None):
    return path_value(value) if value else path_value(read_json(ROOT / 'out/latest-build.json')['directory'])


def adb_result(s, serial, *args, check=True, input_bytes=None):
    if not s['adb'] or not s['adb'].is_file():
        raise RuntimeError('找不到 adb，先 doctor')
    return command([s['adb'], '-s', serial, *args], check=check, timeout=60, input_bytes=input_bytes)


def prepare_observation_permissions(adb):
    user = adb('shell', 'am', 'get-current-user')
    if not user.isdigit():
        raise RuntimeError('无法确认手机当前用户')
    permissions = ('BLUETOOTH_CONNECT', 'BLUETOOTH_SCAN')
    # Some installers revoke new runtime grants shortly after reporting Success.
    # Read back the current user's grants after settling, not just pm's exit code.
    for _ in range(3):
        time.sleep(1)
        for permission in permissions:
            adb('shell', 'pm', 'grant', '--user', user, PACKAGE, 'android.permission.' + permission)
        time.sleep(1)
        package = adb('shell', 'dumpsys', 'package', PACKAGE)
        section = re.search(r'(?ms)^\s*User ' + re.escape(user) + r':.*?(?=^\s*User \d+:|\Z)', package)
        if section and all(re.search(r'android\.permission\.' + permission + r': granted=true', section.group())
                           for permission in permissions):
            return {'user': int(user), 'granted': list(permissions)}
    raise RuntimeError('附近设备权限未保持生效；请在手机设置中允许探针的附近设备权限')


def validate_notification(value):
    if not isinstance(value, dict) or set(value) != {'title', 'content'}:
        raise ValueError('通知文件只允许 title 和 content 两个字段')
    for key, limit in (('title', 80), ('content', 500)):
        text = value[key]
        if (not isinstance(text, str) or not text.strip() or len(text) > limit
                or any(unicodedata.category(c) in ('Cc', 'Cs') and c != '\n' for c in text)):
            raise ValueError(f'通知 {key} 为空、过长或含控制字符')
    return value


def read_notification(path):
    raw = Path(path).read_bytes()
    if len(raw) > 8192:
        raise ValueError('通知文件不能超过 8192 字节')
    return validate_notification(json.loads(raw.decode('utf-8-sig')))


def legacy_diagnostic_launch_enabled():
    """Legacy shell launches are unavailable once diagnostics are private app components."""
    path = ROOT / 'app/AndroidManifest.xml'
    if not path.is_file():
        return False
    ns = '{http://schemas.android.com/apk/res/android}'
    activities = ET.parse(path).getroot().findall('application/activity')
    private_names = {'.ProbeActivity', '.ObserveActivity', '.SdkProbeActivity'}
    return all(any(a.get(ns + 'name') == name and a.get(ns + 'exported') == 'true' for a in activities)
               for name in private_names)


def run_device(s, serial, execute=False, output=None, target_address=None, sdk_mode=None, pairing_ready=False, notification_file=None, session_seconds=1800):
    report = {'serial': serial, 'run_requested': execute, 'official_package_modified': False}
    if sdk_mode not in (None, 'sdk-discover', 'sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session') or (sdk_mode and not target_address):
        raise ValueError('SDK mode requires a valid mode and explicit target address')
    if pairing_ready and sdk_mode not in ('sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session'):
        raise ValueError('pairing_ready is only valid for SDK connection modes')
    if type(session_seconds) is not int or not 60 <= session_seconds <= 1800:
        raise ValueError('session_seconds must be 60–1800')
    notification_bytes = None
    if notification_file is not None:
        if sdk_mode != 'sdk-text':
            raise ValueError('notification_file requires sdk-text')
        notification_bytes = json.dumps(read_notification(notification_file), ensure_ascii=False).encode('utf-8')
        report['notification_input_sha256'] = hashlib.sha256(notification_bytes).hexdigest()
    if target_address is not None:
        if not re.fullmatch(r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}', target_address):
            raise ValueError('Invalid target Bluetooth address')
        target_address = target_address.upper()
        report.update(mode=sdk_mode or 'observe', target_address=target_address)
        if sdk_mode in ('sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session'):
            report['pairing_ready_confirmed'] = pairing_ready
    if execute and not legacy_diagnostic_launch_enabled():
        raise RuntimeError('0.13 已关闭外部诊断入口；run --execute 不再支持，也不会安装或操作手机。请自行安装构建 APK 后从手机 App 连接；观察台和 session 工具仍可使用。')
    started = False
    keep_session = False
    run_dir = fresh_output_dir('runs')
    def adb(*args, check=True):
        return adb_result(s, serial, *args, check=check).stdout.decode('utf-8', errors='replace').strip()
    try:
        if adb('get-state') != 'device':
            raise RuntimeError('设备未就绪')
        report['sdk_int'] = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
        if report['sdk_int'] < 29:
            raise RuntimeError('需要 Android API 29 及以上')
        if target_address and report['sdk_int'] < 31:
            raise RuntimeError('蓝牙探针需要 Android API 31 及以上')
        output = latest_output(output)
        verify(s, output)
        report['apk_sha256'] = digest(output / 'rayneo-init-probe.apk')
        report['status'] = 'ready'
        if execute:
            try:
                active = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/result.json', check=False))
            except ValueError:
                active = {}
            if active.get('status') == 'sdk_session_ready' and str(active.get('pid')) == adb('shell', 'pidof', PACKAGE, check=False):
                raise RuntimeError('持续会话已运行；请用 session.py send/status/stop，不要覆盖安装')
            report['install_output'] = adb('install', '-r', str(output / 'rayneo-init-probe.apk'))
            if 'Success' not in report['install_output']:
                raise RuntimeError('安装未成功')
            if target_address:
                report['observation_permissions'] = prepare_observation_permissions(adb)
            adb('shell', 'am', 'force-stop', PACKAGE)
            adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/result.json', 'files/result.tmp')
            started = True
            activity = '.ObserveActivity' if target_address else '.ProbeActivity'
            extras = ['--es', 'target_address', target_address] if target_address else ['--ez', 'autorun', 'true']
            if sdk_mode:
                activity = '.SdkProbeActivity'
                extras += ['--ez', 'connect', str(sdk_mode in ('sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session')).lower(),
                           '--ez', 'pairing_ready', str(pairing_ready).lower()]
                if sdk_mode in ('sdk-status', 'sdk-text', 'sdk-session'):
                    extras += ['--es', 'business_probe', sdk_mode.removeprefix('sdk-')]
                if sdk_mode == 'sdk-session':
                    extras += ['--ei', 'session_seconds', str(session_seconds)]
                if notification_bytes is not None:
                    adb_result(s, serial, 'shell', '-T', 'run-as', PACKAGE, 'sh', '-c',
                               "'cat > files/notification.json'", input_bytes=notification_bytes)
                    extras += ['--ez', 'custom_notification', 'true']
            report['launch_output'] = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/' + activity, *extras)
            terminal = {'sdk-discover': 'sdk_discovery_completed', 'sdk-connect': 'sdk_auth_passed', 'sdk-status': 'sdk_status_passed', 'sdk-text': 'sdk_text_sent', 'sdk-session': 'sdk_session_ready'}.get(
                sdk_mode, 'observation_completed' if target_address else 'initialization_passed')
            for _ in range(45 if sdk_mode in ('sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session') else 18):
                try:
                    result = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/result.json', check=False))
                except ValueError:
                    result = {}
                if result.get('status') in ('failed', 'blocked_or_failed', terminal) and result.get('stages'):
                    report['probe_result'] = result
                    break
                time.sleep(2)
            pid = adb('shell', 'pidof', PACKAGE, check=False)
            if pid.isdigit():
                (run_dir / 'probe-logcat.txt').write_text(adb('logcat', '-d', '--pid=' + pid, '-t', '300', check=False), encoding='utf-8')
            if 'probe_result' not in report:
                raise RuntimeError('观察窗口内无最终结果')
            report['status'] = report['probe_result']['status']
            if sdk_mode == 'sdk-session' and report['status'] == 'sdk_session_ready':
                keep_session = str(report['probe_result'].get('pid')) == adb('shell', 'pidof', PACKAGE, check=False)
                report['session_kept_running'] = keep_session
                if not keep_session:
                    report['status'] = 'blocked_or_failed'
    except Exception as e:
        report.update(status='blocked_or_failed', error=str(e))
    finally:
        if started and not keep_session:
            try:
                adb('shell', 'am', 'force-stop', PACKAGE)
                if notification_bytes is not None:
                    adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/notification.json')
                # Distinguish absent PID (exit 1) from disconnected/unauthorized ADB.
                if adb('get-state') != 'device':
                    raise RuntimeError('无法确认设备仍在线')
                p = adb_result(s, serial, 'shell', 'pidof', PACKAGE, check=False)
                report['process_stopped'] = p.returncode == 1 and not p.stdout.strip() and not p.stderr.strip()
                if not report['process_stopped']:
                    report['cleanup_error'] = '未能确认探针进程已停止'
            except Exception as e:
                report['process_stopped'] = False
                report['cleanup_error'] = str(e)
        write_json(run_dir / 'device-run.json', report)
    report['result_file'] = str(run_dir / 'device-run.json')
    return report


def main():
    p = argparse.ArgumentParser(description='雷鸟初始化、发现与单目标 SDK 连接实验；不自动解绑')
    p.add_argument('--config', help='本机配置 JSON；不指定则自动探测工具')
    sub = p.add_subparsers(dest='action', required=True)
    sub.add_parser('doctor', help='只检查工具、样本和签名文件，不连接设备')
    sub.add_parser('private-check', help='仅原私有迁移环境：核对私有文件；研究候选包构建不需要此命令')
    b = sub.add_parser('bootstrap', help='准备固定版本 Windows x64 工具')
    b.add_argument('--offline', action='store_true')
    sub.add_parser('build', help='构建并核对实包、原始字节、签名与对齐')
    v = sub.add_parser('verify', help='验证已有构建')
    v.add_argument('--output')
    r = sub.add_parser('run', help='默认仅检查设备；--execute 才安装和运行')
    r.add_argument('--serial', required=True)
    r.add_argument('--execute', action='store_true')
    r.add_argument('--output')
    o = sub.add_parser('observe', help='读取系统连接并限时扫描指定眼镜；不建立自有连接')
    o.add_argument('--serial', required=True)
    o.add_argument('--address', required=True, help='从当前设备确认的眼镜蓝牙地址')
    o.add_argument('--execute', action='store_true')
    o.add_argument('--output')
    for name in ('sdk-discover', 'sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session'):
        sdk = sub.add_parser(name, help='厂商 SDK 发现/首次连接实验；默认只检查就绪')
        sdk.add_argument('--serial', required=True)
        sdk.add_argument('--address', required=True)
        sdk.add_argument('--execute', action='store_true')
        sdk.add_argument('--output')
        if name == 'sdk-session':
            sdk.add_argument('--session-seconds', type=int, default=1800)
        if name == 'sdk-text':
            sdk.add_argument('--notification-file', help='本地 JSON，仅 title/content；不提供时发送固定测试文案')
        if name in ('sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session'):
            sdk.add_argument('--pairing-ready', action='store_true',
                             help='确认已完成官方解绑、系统移除旧配对并进入配对模式；否则执行仅做手机前置检查')
    args = p.parse_args()
    try:
        s = settings(args.config)
        if args.action == 'private-check':
            result = check_private()
        elif args.action == 'bootstrap':
            result = bootstrap(args.offline)
        elif args.action == 'doctor':
            result = doctor(s)
        elif args.action == 'build':
            result = build(s)
        elif args.action == 'verify':
            result = verify(s, latest_output(args.output))
        elif args.action == 'observe':
            result = run_device(s, args.serial, args.execute, args.output, args.address)
        elif args.action in ('sdk-discover', 'sdk-connect', 'sdk-status', 'sdk-text', 'sdk-session'):
            result = run_device(s, args.serial, args.execute, args.output, args.address,
                                args.action, getattr(args, 'pairing_ready', False), getattr(args, 'notification_file', None), getattr(args, 'session_seconds', 1800))
        else:
            result = run_device(s, args.serial, args.execute, args.output)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        failed = (result.get('status') in ('failed', 'blocked_or_failed')
                  or result.get('process_stopped') is False
                  or (args.action == 'doctor' and not (result['build_ready'] and result['adb_ready'])))
        return 2 if failed else 0
    except Exception as e:
        print(json.dumps({'status': 'error', 'error': str(e)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
