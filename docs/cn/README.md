# Aixleo iO Android 0.2 系列（实际版本 0.2.1）

[项目首页](../../README.md) | [English](../en/README.md)

这是适配雷鸟 iO 的非官方 Android 研究 App，不是厂商发布的 SDK，也还没有稳定的独立 AAR。0.2.1 提供源码、固定版本的设备通信依赖和构建脚本；不提供维护者的 APK、签名或云服务密钥。

<p align="center">
  <a href="../assets/upgrade-0.2-zh.png"><img src="../assets/upgrade-0.2-zh.png" alt="Aixleo iO Android 0.2 系列升级总览：面向雷鸟 RayNeo iO" width="720"></a>
</p>

点击图片查看原图；功能验证范围与已知限制以正文及关联文档为准。


眼镜连接、原生录音、本地 Ogg Opus 保存、云端语音问答、手机眼镜待办同步、天气温度与手机校时、通知转发和部分设备设置已有实现；各项的真机覆盖程度不同。[功能与验证边界](FEATURES-0.2.1.md)把它们分开说明。官方界面可见但尚未由本 App 支持的配置见[设备能力参考](DEVICE-CAPABILITIES.md)。

按[构建与首次使用](BUILDING.md)自行编译和安装。连接前先结束官方 App 对同一副眼镜的会话及进程；已做的解绑实测未观察到眼镜数据清空，后续官方 App 或固件版本的行为尚未验证，解绑不能当成普通重连。录音不会自动转写，问答服务需自备账号与密钥，天气需位置与网络，通知需系统授权。操作时的常见问题见[排查指南](TROUBLESHOOTING.md)。

构建后的手机应用名为 **AIX IO SDK Lab**，版本 0.2.1；项目名称与手机图标标题不同。使用 AI 辅助开发时，从修改任务定位源码和测试，无需先阅读完整 Android 逆向资料。

## 当前使用与开发指南

以下正文随当前源码维护。当前版本以[版本文件](../../app/lab-version.json)为准；GitHub 的文件日期是最后修改时间，通用说明未改动不代表失效。

| 想了解什么 | 阅读入口 |
| --- | --- |
| 能做什么、验证到哪一步 | [功能与边界](FEATURES-0.2.1.md) · [兼容性](COMPATIBILITY.md) |
| 如何构建、安装和配置 | [构建与首次使用](BUILDING.md) |
| 连接或使用出了问题 | [排查指南](TROUBLESHOOTING.md) |
| 让 AI 接手或自己继续开发 | [AGENTS.md](../../AGENTS.md) · [开发指南](DEVELOPMENT.md) |
| 已探索参数与官方可见能力 | [能力与参数](CAPABILITY-MAP.md) · [设备能力参考](DEVICE-CAPABILITIES.md) |
| 如何测试和观察 | [测试指南](TESTING.md) · [命令行工具](TOOLS.md) · [观察台](OBSERVER.md) |
| 接入自己的知识库服务 | [Gateway 契约](GATEWAY.md) |
| 数据、许可和第三方来源 | [隐私](PRIVACY.md) · [安全](SECURITY.md) · [许可](LICENSING.md) · [第三方说明](THIRD_PARTY_NOTICES.md) · [来源与致谢](PROVENANCE.md) |

## 维护流程

[发布检查清单](RELEASE-CHECKLIST.md)和[文档维护约定](DOCUMENTATION.md)供维护者使用，按流程变化更新，不要求每次版本升级都改写。

## 版本变化与验证记录

- [变更记录](CHANGELOG.md)：当前版本的变化，以及保留的历史版本条目。
- [审核索引](AUDIT.md)：区分当前源码验证和旧版记录；[0.2.1 验证记录](audits/2026-09-26-0.2.1.md)列出本次证据及未覆盖项。
- 历史：[0.14](audits/2026-09-12-0.14.md) · [0.13](audits/2026-09-12-0.13.md)。仅用于追溯当时版本，不作为当前版本的操作或验收依据。

根目录与 `docs/` 下同名短页只保留旧链接；中文正文集中在 `docs/cn/`，英文正文集中在 `docs/en/`。
