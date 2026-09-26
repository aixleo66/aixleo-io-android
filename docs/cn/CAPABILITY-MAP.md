# 开发能力清单与参数入口（0.2.1）

[English](../en/CAPABILITY-MAP.md) · [用户功能表](FEATURES-0.2.1.md) · [官方界面能力](DEVICE-CAPABILITIES.md)

本页从随包源码整理，便于继续开发，不是官方 SDK 全文或独立可导入的 AAR。下列类多数为包内实现，需要连同 App 的会话和生命周期使用，不能当成任意时刻可调用的独立 API。通信依赖来源固定在官方 App 1.0.2（68）；部分配置适配参考官方 1.0.4，固件兼容与参数语义须分别验证。

## 已实现的能力与源码位置

“实现”表示有代码路径；具体真机覆盖见功能表。电脑离线测试、手机发送成功、眼镜读回以及佩戴观察是不同证据。

| 能力 | 提供给后续开发的内容 | 源码入口 |
| --- | --- | --- |
| 设备连接与业务会话 | 已配对设备连接、认证、会话就绪检查、命令互斥、断开/重连处理 | [SdkProbeActivity](../../app/src/SdkProbeActivity.java)、[SessionOwnership](../../app/src/SessionOwnership.java)、[SessionCommandGate](../../app/src/SessionCommandGate.java) |
| 事件与状态 | 设备事件接收、原始状态中已确认字段的筛选；不把电量回包直接视为业务就绪 | [ReportedStatusPolicy](../../app/src/ReportedStatusPolicy.java)、[ReportedSettingsPolicy](../../app/src/ReportedSettingsPolicy.java) |
| 助手音频与回答 | 眼镜收音、云端 ASR、可配置回答服务、原生回答显示；取消/旧轮次隔离 | [StreamingAsr](../../app/src/StreamingAsr.java)、[CloudConfig](../../app/src/CloudConfig.java)、[SdkProbeActivity](../../app/src/SdkProbeActivity.java) |
| 续问与短期上下文 | 续问事件接纳与计时；内存上下文最多 6 轮、4000 个 Java 字符，超额裁剪或拒绝入历史；不是永久聊天数据库 | [VoiceWakePolicy](../../app/src/VoiceWakePolicy.java)、[DisplayedAnswerPolicy](../../app/src/DisplayedAnswerPolicy.java)、[AssistantConversation](../../app/src/AssistantConversation.java) |
| 普通录音 | 原生菜单/手机入口、音频接收、本地 Ogg Opus 保存、标记和回放 | [RecordingAudio](../../app/src/RecordingAudio.java)、[OggOpusWriter](../../app/src/OggOpusWriter.java)、[RecordingMarkStore](../../app/src/RecordingMarkStore.java)、[CloudActivity](../../app/src/CloudActivity.java) |
| 待办双向协作 | 手机整表同步、眼镜勾完成回传；明确语音口令在手机创建再同步到眼镜 | [TodoGlassesClient](../../app/src/TodoGlassesClient.java)、[TodoFullSync](../../app/src/TodoFullSync.java)、[TodoStore](../../app/src/TodoStore.java)、[VoiceTodoAction](../../app/src/VoiceTodoAction.java) |
| 天气与时间 | 手机定位 → Open-Meteo → 温度/天气载荷；连接就绪和约 15 分钟调度触发；时间随手机同步 | [PhoneWeather](../../app/src/PhoneWeather.java)、[WeatherSync](../../app/src/WeatherSync.java)、[WeatherPolicy](../../app/src/WeatherPolicy.java)、[SdkProbeActivity](../../app/src/SdkProbeActivity.java) |
| 通知 | Android 系统通知过滤、来源选择、转发；0.2.1 新增前台服务常驻通知过滤（新增判据为 `FLAG_FOREGROUND_SERVICE` 或 `service` 类别；未命中新判据的通知仍须通过原有 ongoing、群组摘要、静音和来源选择等筛选，并非一律转发） | [NotificationPolicy](../../app/src/NotificationPolicy.java)、[NotificationSettingsUi](../../app/src/NotificationSettingsUi.java) |
| 电池/充电盒 | 电量及入盒、盒盖等事件的观察入口；事件枚举和持续刷新需按证据使用 | [SdkProbeActivity](../../app/src/SdkProbeActivity.java)、[ReportedStatusPolicy](../../app/src/ReportedStatusPolicy.java) |
| 设备设置 | 设置写入构造、读回比较与有限的可恢复试验；参数见下表 | [GlassesSettingWrite](../../app/src/GlassesSettingWrite.java)、[RayNeoCrownSettings](../../app/src/RayNeoCrownSettings.java)、[RayNeoHeadSettings](../../app/src/RayNeoHeadSettings.java)、[RayNeoWakeSettings](../../app/src/RayNeoWakeSettings.java) |
| 可选远端知识库 | WSS 客户端、结构化回答/来源、断线续传和截止时间；不含服务端或任意远程控制 | [KnowledgeClient](../../app/src/KnowledgeClient.java)、[KnowledgeRunState](../../app/src/KnowledgeRunState.java)、[接入契约](GATEWAY.md) |

## 设置参数：实现范围与验证范围分开

以下是本项目写入构造器的约束，**不是厂商承诺的通用参数范围**。联动设置先读取本轮完整原值，只改变目标项并保留其余值；字段缺失不要补零猜写。

| 设置 | 本项目参数/结构 | 已知验证与边界 |
| --- | --- | --- |
| 手动镜片亮度 | `brightness` 的 `value` 为整数 1–17；UI 映射为 0–100%；设备消息为 `brightness_change`，类型 2 | 有档位和镜片对照；1 对应界面 0% 不表示关闭屏幕。外部指示灯亮度是另一个设置。 |
| 自动息屏 | `auto_lock` 的 `value` 只能为 5/10/15/25/40/60/120 秒；消息 `auto_lock_time` 的辅助 `mode`/`data` 明确为 null | 5 秒、10 秒有写入/读回/观察；不能把息屏时长当成助手续问或回答退出时长。 |
| 表冠方向及预置动作 | `direction`: 0 标准/1 自然；`double` 和 `longPress` 必须一起保留发送；消息 `crown_config` 的 `data` 是 JSON **字符串** | 方向反转有对照；动作构造器允许 0–9，息屏长按排除 6；此处不发布未经逐动作实测的全套动作名称。亮屏长按不等于可随意改写。 |
| 头控 | `enabled` 为 0/1；`mode`: 0 点头确认/1 摇头确认；`head_gestures` 在 mode 携带方向 | 改方向需保留原开关。读写与佩戴验证覆盖不同，按[功能表](FEATURES-0.2.1.md)使用。 |
| 抬头唤醒及息屏互动 | `headup_switch` 0/1、`headup_degree`、`crown_switch` 三字段一起写；`wakeup_config.data` 为 JSON 字符串 | 代码接受角度整数 0–90、crown_switch 0–3；**官方采集页角度盘为 0–70°，范围存在差别，不能据此认定 71–90° 已可用**。5°/60°对照只证明差异生效，不证明角度精度；crown_switch 不是简单布尔量。 |

`GlassesSettingWrite` 的其余设置消息类型为 5；这是当前适配实现。`Lab*Trial` 类会保存原值、临时修改并恢复，用于实验；产品入口的持久设置写入不会自动恢复，两者不要混用。

## 已有读取线索、尚未承诺可写的能力

| 字段组 | 本项目保留的字段/意义 | 不能据此推出的结论 |
| --- | --- | --- |
| 显示 | `generalSettings.displayConfig.height` / `distance` | 读到数值不等于已支持调节高度/距离。 |
| 隐私/指示灯 | `generalSettings.privacyConfig.mic_switch` / `led_light` / `led_auto_light` / `log_switch` | 外部灯自动亮度与镜片自动亮度不同；尚无完整自有写入闭环。 |
| 存储 | `generalSettings.storageTotal` / `storageUsed` | 不代表可清理、导出全部设备文件；单位需结合设备记录确认。 |
| 状态回报 | `battery`、`brightness`、`automaticBrightness`、`batt_temp`、`isCharging`、`chargeType`、`screenStatus`、`hallStatus`、`micStatus`、`focusMode` | 筛选器保留有限数值/布尔量；不猜枚举意义，也不承诺每次回报都包含所有字段。 |
| 勿扰结构 | `enable`、`enableGlassClose`、`policy.auto/type/begin/end/weekday`（数值/布尔时） | 结构线索不是可用的定时勿扰编辑器；字符串、数组不会按数值字段保留。 |

配置回报里的表冠、头控、唤醒字段采用 camelCase，例如 `headupSwitch`；写入参数采用构造器要求的字段，例如 `headup_switch`。不要把读回快照原样当成写入载荷。状态读取与设置读取走不同通道，按会话命令队列顺序调用。

## 官方可见而本项目未完成的方向

实时字幕/翻译、看板组件安装、自定义唤醒词、官方云模型选择、提醒/云账号同步、完整区域与单位配置、所有录音后处理等，见[官方能力参考](DEVICE-CAPABILITIES.md)。它们可以用于安排进一步开发，不能在 README 中列成当前可用功能。翻译/字幕试验入口当前被禁用，页面存在不代表通道成功。

新增能力建议按“源码适配 → 离线载荷/状态测试 → 设备回执 → 镜片或声音观察”记录结果。公开反馈只提供脱敏复现步骤，不上传私有 SDK 全文、APK 解包结果或个人原始日志。
