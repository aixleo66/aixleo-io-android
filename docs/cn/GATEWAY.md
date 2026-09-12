[简体中文](GATEWAY.md) | [English](../en/GATEWAY.md)

# 可选知识库 Gateway 契约（实验性参考）

## 来源与本版范围

这里的协议沿用 **Rokid Local Harness Bridge**（`rokid-local-harness-bridge`，简称 Rokid Harness）项目，最初为 Rokid 眼镜连接电脑端本地 Codex 与知识库而设计，因此仍保留 `rokid-harness.v1` 这个协议名称。它是雷鸟 Android 客户端连接本地 Codex 的一种探索方式，不是唯一方式，也不是本项目要求使用的标准方案；更多、更灵活的接入方式仍在探索中。

**本版没有包含 Rokid Harness 工程或其服务端实现，也不在本版中新增或分发它。** 仓库仅保留 Android 侧的实验性兼容客户端（`KnowledgeClient.java`、`KnowledgeRunState.java`）及相关配置。电脑端 Gateway、Codex 调用适配、知识库检索服务、隧道启动脚本和部署配置均未随本仓库提供。

该接入方案尚未在本版完成充分的可用性与稳定性验证。历史开发中的连接、问答尝试不等于可供使用者直接部署的完整验收；下文只是现有客户端的协议参考，不构成已交付或已验证的 Harness 服务承诺。可以跳过这项实验功能，使用独立的模型服务配置；录音、通知等功能不要求部署 Rokid Harness。

## 现有客户端协议

本包只有 Android 客户端，没有附带电脑端 Gateway、知识库或 Codex 运行环境。此接口是 `rokid-harness.v1` 专用 WebSocket 协议，不能填任意 OpenAI 兼容 HTTP 地址代替。行为以 `app/src/KnowledgeClient.java` 和 `KnowledgeRunState.java` 为准。

配置 `wss://你的域名/服务路径` 和独立 Token。客户端拒绝非 WSS、URL query、userinfo 与 fragment；Token 放在连接后的 hello 消息中，不放 URL。临时隧道更换域名后须更新配置；服务端负责认证及实际访问权限。

最小成功交互（均为 JSON 文本消息；下列 Token 和内容只是占位）：

```json
{"type":"hello","token":"YOUR_TOKEN"}
{"type":"sync","protocol":"rokid-harness.v1"}
{"type":"prompt","prompt":"用户问题","capability":"read"}
{"type":"event","runId":"example-run","seq":1,"event":{"type":"system"}}
{"type":"event","runId":"example-run","seq":2,"event":{"type":"result","displayAnswer":"完整回答","spokenAnswer":"简短回答","sources":[{"path":"notes/example.md","title":"资料标题"}]}}
{"type":"runEnd","runId":"example-run","status":"done"}
```

第一、三条由客户端发送，其余由服务端发送。第一个 event 必须是 seq=1 的 system。服务端可发送其他有序 event，但必须保持同一 runId，seq 按协议连续递增；重复事件会被忽略，缺序会失败。最终需要且只能有一个结构化 result，再以成功 runEnd 结束；日志或叙述事件不会被当作回答。

问题须为 1–2000 字符；displayAnswer 和 spokenAnswer 必须非空，上限分别为 16000/2000 个 Unicode 码点。sources 必须是数组，可以为空，最多 50 项；每项 path/title 必填，长度最多 1024/300。path 使用相对路径，不得返回电脑盘符或绝对路径。客户端不证明来源确实被检索，真实性由服务端负责。

客户端收到 runId 后若断线，最多重连一次，在 hello 附加 `lastRunId` 和 `lastSeq`，要求服务端支持续传；不会自动重新提交 prompt。鉴权/协议错误、拒绝或执行 error 会失败；整次请求有 150 秒期限。空闲时客户端不保持知识库连接。

本地取消关闭等待和连接，但当前客户端没有向服务端发送任务取消命令，因此不能保证电脑端立即停止运行或停止计费。服务端需自行管理断连和任务生命周期。`capability: read` 是请求约定，权限必须由服务端执行；手机不会展示或代替用户批准电脑端写入、命令执行等高权限操作。
