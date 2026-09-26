# 命令行工具

[English](../en/TOOLS.md) · [构建](BUILDING.md)

手机 App 是正常使用入口。以下工具用于开发诊断，先执行 `python 工具名.py --help` 查看当前参数；本机配置/输出不应公开。

| 工具 | 用途 | 外部影响 |
| --- | --- | --- |
| `lab.py` | bootstrap、doctor、build、verify | bootstrap 下载工具；build 使用自己的调试签名；旧 `run --execute` 被禁止，不会替你完成正常配对。 |
| `verify_source.py` | 分发、工作区或 Git 提交的文件清单/哈希校验 | 默认只检查文件；不是设备测试。 |
| `display_observer.py` | 读取现有手机会话并在本机浏览器重绘 | 通过 ADB 读状态，默认 30 分钟；不控制眼镜。人工观察确认保存在电脑 `out/visual-confirmations`，不是自动镜片证据。 |
| `idle_check.py` | 对已有会话进行有时限的状态采样 | ADB 只读；不会发起问答，输出含会话数据。 |
| `session.py` | 查看会话及向现有会话下发明确命令 | 查询读状态；send 类动作会真正触发通知、录音、待命或设置等操作，先看 help 与源码白名单。不要把它当无副作用测试。 |
| `model_probe.py` | 使用电脑配置验证一次短文本模型回复，可选向眼镜发通知 | 不加 `--execute` 只校验配置；加后向配置的服务发问题，可能计费；加 `--send-to-glasses --use-session` 才通过当前会话下发，需要 serial。它是独立探针，不是手机助手的完整 ASR 链路。 |

`display_observer.py`、`idle_check.py`、`model_probe.py` 默认使用 `sdk-lab`，旧包才选 `--profile daily`。`session.py` 也应显式确认 profile，避免读错应用。不要同时运行两个写设备的测试。

电脑模型诊断可从 `llm.example.json` 复制为 `llm.local.json`，填自己的 endpoint/model/api_key；不覆盖已有文件。默认检查示例：

```powershell
python model_probe.py --config llm.local.json
```

这个命令不发问答请求。主动增加 `--execute --prompt "用一句话介绍你自己"` 才真实访问服务；使用前核对服务归属。响应和问题写入 `out/model-runs`，不能把目录直接上传。旧的非会话投递路径被拒绝，请先从手机 App 建立会话，再按 help 使用 `--use-session`。
