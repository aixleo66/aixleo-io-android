[简体中文](PROVENANCE.md) | [English](../en/PROVENANCE.md)

# 来源记录与 Turbo IO 致谢

本项目参考了 [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO) 的 iOS 实现与研究文档，感谢原作者及贡献者。Android 实现由本项目单独维护，未获得上游背书。

来源核对使用的上游基线为 [`9382ec6791c70cb97546ffc0a08ca87bc3050746`](https://github.com/Turbo1123/Turbo-IO/tree/9382ec6791c70cb97546ffc0a08ca87bc3050746)。这是本次核对基线，不代表所有引用首次发生于该提交，也不代表所有内容适用同一历史许可。

## 已确认的参考关系

| 安卓位置 / 主题 | 上游依据或研究材料 | 目前能确认的关系 |
| --- | --- | --- |
| `analysis/check-synthetic-auth.py` | `rayneo-protocol/Tests/RayNeoProtocolTests/ProtocolTests.swift` | 文件注释明确说明预期测试向量来自 Turbo IO；公式另参考官方 Android 反编译结果。向量与实现的具体许可仍须逐项核对 |
| `app/src/VoiceWakePolicy.java` 与语音会话实现 | Turbo IO 待命/续问研究与 iOS 语音逻辑 | 代码注释及开发记录说明参考 type1/type11 触发与答案窗口；具体表达是否移植、对应源文件和首次引用版本需继续确认 |
| 普通录音控制与解码 | `CompanionDeviceFeatures.swift`、`core-probe/Sources/NativeRecording.c` | 用于录音请求与解码参数对照；Android 实现另结合官方消息模型及真机回执做了调整 |
| 声道与转写研究 | `ManualRecordingASR.swift`、上游架构说明 | 用于左右声道和混音处理的调查，不代表 Android 已实现上游全部处理功能 |
| 浏览器观察台 | 上游显示观察设计与说明 | 参考协议重绘、区分提交文字与镜片实际显示的思路；Android 采用自己的 USB ADB 状态读取路径 |
| 厂商连接适配 | 官方 Android App 的厂商组件 | 直接依赖官方 Android 厂商组件，来源与许可单独列在第三方说明中 |

以上列出目前已记录的参考关系，完整逐文件来源与许可核对仍在进行中。

`analysis/check-synthetic-auth.py` 是未随仓库提供的历史研究脚本，上表保留其测试向量来源。其他 Android 路径属于本仓库，iOS 路径属于上游项目。

相关许可与依赖信息见[许可说明](LICENSING.md)和[第三方说明](THIRD_PARTY_NOTICES.md)。
