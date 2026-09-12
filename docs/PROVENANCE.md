# 来源记录与 Turbo IO 致谢

本项目开发参考了 [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO) 的 iOS 实现与研究文档，感谢原作者及贡献者。本项目单独维护 Android 实现，不暗示获得上游背书，也不将上游研究成果笼统标为本项目原创。

当前用于源码核对的本地上游基线为 [`9382ec6791c70cb97546ffc0a08ca87bc3050746`](https://github.com/Turbo1123/Turbo-IO/tree/9382ec6791c70cb97546ffc0a08ca87bc3050746)。这是本次核对基线，不代表所有引用首次发生于该提交，也不代表所有内容适用同一历史许可。

## 已确认的参考关系

| 安卓位置 / 主题 | 上游依据或研究材料 | 目前能确认的关系 |
| --- | --- | --- |
| `analysis/check-synthetic-auth.py` | `rayneo-protocol/Tests/RayNeoProtocolTests/ProtocolTests.swift` | 文件注释明确说明预期测试向量来自 Turbo IO；公式另参考官方 Android 反编译结果。向量与实现的具体许可仍须逐项核对 |
| `app/src/VoiceWakePolicy.java` 与语音会话实现 | Turbo IO 待命/续问研究与 iOS 语音逻辑 | 代码注释及开发记录说明参考 type1/type11 触发与答案窗口；具体表达是否移植、对应源文件和首次引用版本需继续确认 |
| 普通录音控制与解码 | `CompanionDeviceFeatures.swift`、`core-probe/Sources/NativeRecording.c` | 用于录音请求与解码参数对照；Android 另据官方消息模型及真机回执调整，不能将最终行为都归为原样移植 |
| 声道与转写研究 | `ManualRecordingASR.swift`、上游架构说明 | 用于左右声道和混音处理的调查，不代表 Android 已实现上游全部处理功能 |
| 浏览器观察台 | 上游显示观察设计与说明 | 参考协议重绘、区分提交文字与镜片实际显示的思路；Android 采用自己的 USB ADB 状态读取路径 |
| 厂商连接适配 | 官方 Android App 的厂商组件 | 不是 Turbo IO 可以替厂商授权的内容；单独核对厂商权利与依赖来源 |

以上为有记录支持的初步清单，不是逐行复制关系审计，也不是完整许可结论。“参考设计”“复制/翻译代码”“使用测试向量”“恢复接口”应分别记录，不能混为一类。

其中 `analysis/check-synthetic-auth.py` 是仅保留在本地的历史研究脚本，**不在当前源码预览中**；保留这行用于如实说明研究来源，不表示使用者应该找到或运行该文件。其余安卓源码路径在本仓库中，表内 iOS 路径属于链接的上游仓库。

## 正式发布前的逐文件记录格式

每项至少记录：本项目文件与范围、上游项目与路径、准确提交、取得时的许可、引用方式、改动说明、需保留的版权/许可通知、审查结论。无法确定时标记待核对，不填写虚构作者或授权。

对于复制或移植的文件，应在相应文件/目录中保留要求的原始版权与许可通知，不能只靠 README 的一句致谢替代。根 LICENSE 也不能覆盖厂商或其他上游的权利。

当前上游许可关系及选择见 [许可说明](LICENSING.md) 和 [第三方说明](../THIRD_PARTY_NOTICES.md)。尚未获得上游对本 Android 项目的背书或额外授权，不应使用“官方 Android 版”“经原作者授权”等表述。
