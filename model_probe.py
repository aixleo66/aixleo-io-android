"""One bounded Chat Completions request, optionally shown through our tested notification path."""
import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import lab
import session


class ProbeError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_config(path):
    try:
        config = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    except (OSError, ValueError):
        raise ProbeError('请先填写 llm.local.json 的接口地址、模型名和密钥配置') from None
    if not isinstance(config, dict):
        raise ProbeError('模型配置必须是 JSON 对象')
    base = config.get('base_url', '')
    model = config.get('model', '')
    if not isinstance(base, str) or not isinstance(model, str) or not model.strip():
        raise ProbeError('需要 base_url 和 model')
    url = urllib.parse.urlsplit(base.rstrip('/'))
    if (not url.hostname or url.username or url.password or url.query or url.fragment
            or any(c.isspace() for c in base)
            or not (url.scheme == 'https' or (url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost', '::1')))):
        raise ProbeError('Base URL 必须是 HTTPS 服务根地址；本地回环服务可用 HTTP；不含密钥、查询或用户信息')
    endpoint = base.rstrip('/')
    if not endpoint.endswith('/chat/completions'):
        endpoint += '/chat/completions'
    env = config.get('api_key_env', 'RAYNEO_LLM_API_KEY')
    if not isinstance(env, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', env):
        raise ProbeError('api_key_env 必须为环境变量名')
    key = os.environ.get(env) or config.get('api_key', '')
    if not isinstance(key, str) or not key or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise ProbeError('请在本地配置或指定环境变量中填写有效 API Key；不要放在命令行')
    limit = config.get('max_tokens', 256)
    if type(limit) is not int or not 32 <= limit <= 1024:
        raise ProbeError('max_tokens 范围为 32–1024')
    thinking = config.get('thinking')
    if thinking is not None and thinking not in ('enabled', 'disabled'):
        raise ProbeError('thinking 必须为 enabled 或 disabled')
    result = {'endpoint': endpoint, 'model': model.strip(), 'api_key': key, 'max_tokens': limit}
    if thinking is not None:
        result['thinking'] = thinking
    return result


def parse_answer(raw):
    try:
        value = json.loads(raw)
        choice = value['choices'][0]
        message = choice['message']
        if choice.get('finish_reason') != 'stop' or message.get('tool_calls'):
            raise ProbeError('模型输出未正常结束或请求了工具调用；本轮不发送到眼镜')
        text = message['content']
        lab.validate_notification({'title': '模型回答', 'content': text})
        usage = value.get('usage') or {}
        safe_usage = {key: usage[key] for key in ('prompt_tokens', 'completion_tokens', 'total_tokens')
                      if isinstance(usage, dict) and type(usage.get(key)) is int and usage[key] >= 0}
        return text.strip(), safe_usage
    except ProbeError:
        raise
    except (ValueError, TypeError, KeyError, IndexError):
        raise ProbeError('响应不符合短文字格式，或内容为空/超过500字；未发送到眼镜') from None


def ask(config, prompt, opener=None):
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 2000:
        raise ProbeError('问题须为 1–2000 字的文字')
    payload = {'model': config['model'], 'stream': False, 'max_tokens': config['max_tokens'], 'messages': [
        {'role': 'system', 'content': '你是眼镜上的中文助手。请用不超过100个汉字的纯文本简洁回答，不用Markdown。你没有工具执行能力，不得声称已执行操作。'},
        {'role': 'user', 'content': prompt}]}
    if config.get('thinking') is not None:
        payload['thinking'] = {'type': config['thinking']}
    request = urllib.request.Request(config['endpoint'], method='POST',
        data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={'Authorization': 'Bearer ' + config['api_key'], 'Content-Type': 'application/json'})
    try:
        client = opener or urllib.request.build_opener(NoRedirect())
        with client.open(request, timeout=30) as response:
            if response.status != 200:
                raise ProbeError('模型服务未返回 HTTP 200')
            raw = response.read(1048577)
            if len(raw) > 1048576:
                raise ProbeError('模型响应超过限制')
        return parse_answer(raw)
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        raise ProbeError(f'模型服务返回 HTTP {code}；不输出响应正文或密钥，不自动重试') from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ProbeError('模型连接失败或超时；未自动重试') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=lab.ROOT / 'llm.local.json')
    parser.add_argument('--prompt', default='请用一句话介绍眼镜AI助手可以做什么，控制在40个汉字内。')
    parser.add_argument('--execute', action='store_true', help='执行一次真实 API 请求；否则仅核对配置')
    parser.add_argument('--send-to-glasses', action='store_true')
    parser.add_argument('--serial')
    parser.add_argument('--address')
    parser.add_argument('--pairing-ready', action='store_true')
    parser.add_argument('--use-session', action='store_true', help='复用当前持续连接；不重装、不重新配对')
    args = parser.parse_args()
    report = {'status': 'not_started', 'model_request_attempted': False, 'glasses_requested': args.send_to_glasses}
    output = None
    try:
        config = load_config(args.config)
        report.update(endpoint=config['endpoint'], model=config['model'], max_tokens=config['max_tokens'])
        if args.use_session and (not args.send_to_glasses or not args.serial):
            raise ProbeError('--use-session 需要 --send-to-glasses 和 --serial')
        if args.send_to_glasses and not args.use_session and (not args.serial or not args.address or not args.pairing_ready):
            raise ProbeError('发送到眼镜需要明确 serial/address 和 pairing-ready；不自动解绑')
        if not args.execute:
            report['status'] = 'configured_not_executed'
        else:
            if args.send_to_glasses:
                if args.use_session:
                    expected_session = session.require_live(lab.settings(), args.serial)['session_id']
                else:
                    ready = lab.run_device(lab.settings(), args.serial, target_address=args.address, sdk_mode='sdk-text', pairing_ready=True)
                    if ready['status'] != 'ready':
                        raise ProbeError('手机或构建未就绪；未调用模型')
            output = lab.fresh_output_dir('model-runs')
            report['model_request_attempted'] = True
            started = time.monotonic()
            answer, usage = ask(config, args.prompt)
            report.update(status='model_answer_received', prompt=args.prompt, answer=answer, usage=usage,
                          model_elapsed_ms=round((time.monotonic() - started) * 1000))
            notification = output / 'notification.json'
            lab.write_json(notification, {'title': '模型回答', 'content': answer})
            report['notification_file'] = str(notification)
            if args.send_to_glasses:
                if args.use_session:
                    device = session.send(lab.settings(), args.serial, 'notify', lab.read_notification(notification), expected_session)
                    delivered = device['status'] == 'completed'
                else:
                    device = lab.run_device(lab.settings(), args.serial, execute=True, target_address=args.address,
                        sdk_mode='sdk-text', pairing_ready=True, notification_file=notification)
                    delivered = device['status'] == 'sdk_text_sent' and device.get('process_stopped')
                report['device_result_file'] = device['result_file']
                report['status'] = 'model_answer_sent' if delivered else 'device_delivery_failed'
                report['lens_verified'] = False
    except (ProbeError, ValueError) as error:
        report.update(status='blocked_or_failed', error=str(error))
    except (RuntimeError, OSError):
        report.update(status='blocked_or_failed', error='本地构建、设备或文件操作失败；检查本地测试结果')
    if output:
        lab.write_json(output / 'model-run.json', report)
        report['result_file'] = str(output / 'model-run.json')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 2 if report['status'] in ('blocked_or_failed', 'device_delivery_failed') else 0


if __name__ == '__main__':
    raise SystemExit(main())
