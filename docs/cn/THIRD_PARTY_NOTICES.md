[简体中文](THIRD_PARTY_NOTICES.md) | [English](../en/THIRD_PARTY_NOTICES.md)

# 第三方组件与来源说明（草稿）

本清单描述当前分支包含的依赖，随依赖或来源变化更新，不是厂商授权证明。发布前须与实际源码、依赖、构建输出一致；只列名称而不保留许可证原文，不构成完整的许可履行。

特别感谢 [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO) 的原作者与贡献者。本项目已在首页注明 iOS 项目参考关系；具体核对线索见 [来源记录](PROVENANCE.md)。致谢不替代适用的版权和许可通知。

| 组件 / 来源 | 当前用途 | 发布处理 |
| --- | --- | --- |
| Java-WebSocket 1.5.6 | 实时 ASR 与 Gateway 的 WebSocket 连接 | MIT；保留上游版权及许可原文，依赖来源和哈希已有本地清单 |
| slf4j-api / slf4j-nop 2.0.6 | WebSocket 日志接口与空实现 | MIT；保留上游版权及许可原文 |
| 雷鸟官方 App 中的厂商通信代码 | 研究版设备连接、认证和消息收发 | 源码预览附三份原样 DEX 和两份协程服务声明，封装为 vendor-payload.jar；自行构建的 APK 会包含该依赖，本仓库不附 APK；来源版本 1.0.2（68），哈希见 vendor 清单。厂商再分发权未核实，根许可证不覆盖它们 |
| Turbo IO | 业务流程、协议/测试向量及部分实现的参考来源 | 必须逐文件标明引用/移植范围、提交和许可；不能笼统标为所有内容均为 MIT |
| Android SDK / JDK / Python 等工具 | 编译和开发 | 由开发者按各自许可取得；不发布私人缓存、工具安装包或签名材料 |

Turbo IO 当前原创部分使用 PolyForm Noncommercial 1.0.0，并在其说明中保留历史 MIT 版本已授予的权利。其厂商 framework 等第三方组件不受根许可证重新授权。参见 [上游许可](https://github.com/Turbo1123/Turbo-IO/blob/main/docs/LICENSING.md) 和 [上游第三方说明](https://github.com/Turbo1123/Turbo-IO/blob/main/THIRD_PARTY_NOTICES.md)。

凡从上游复制、翻译或改写的受保护内容，均应核对实际适用许可并履行条件；单纯功能相似、兼容所需事实或独立实现需结合来源记录分别判断，不能仅靠文件扩展名或编程语言判断。

厂商名称仅用于标识互操作目标。维护者不代表厂商，也不授予对厂商软件、接口材料、商标或服务的额外权利。

已经附带：app/lib 中 Java-WebSocket、SLF4J 的许可证原文，以及 third-party/Turbo-IO/LICENSE；构建流程也会把前两者许可放入生成的 APK assets。尚待补齐的是完整逐文件来源表、移植内容对应的具体版权通知及厂商依赖适用条款。不要把已附许可证等同于“全部授权已确认”。
