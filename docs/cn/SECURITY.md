[简体中文](../cn/SECURITY.md) | [English](../en/SECURITY.md)

# 安全机制与使用边界

本文描述当前分支的安全设计与保留风险，随实际行为变化更新。具体缺陷的受影响版本、修复记录、测试数量和真机验证状态见[审核索引](AUDIT.md)及其历史快照；不能把设计说明当成所有版本均已通过验证。

## 诊断入口与会话隔离

- 诊断 Activity 不向普通外部 App 导出；主界面保留正常启动入口。不要为了兼容旧脚本重新导出诊断组件。
- 会话归属检查限制非持有实例清理或写入共享状态；清理步骤分别执行，超时决策在主线程进行。
- 样本 ASR 诊断需要用户显式确认上传，并有单次启动保护、流创建/启动与销毁取消的协调。用户主动诊断仍可能调用云服务；这条样本路径不打开手机麦克风。
- `lab.py run --execute` 在安装或操作手机前拒绝旧诊断启动路径。自行构建、安装并从手机 App 启动连接。

源码依据包括 `app/AndroidManifest.xml`、`app/src/SdkProbeActivity.java`、`app/src/StreamSmokeActivity.java` 及相关会话控制代码。修改这些机制时，应按[测试指南](TESTING.md)验证外部启动拒绝、重复/过期实例、并发和 App 内部正常使用，并记录实际结果。

## 保留的调试能力

当前构建仍为 `debuggable=true`，不是生产加固版本。经用户授权的 ADB `run-as` 可读取应用私有状态并向现有会话发送命令；这不等于诊断组件向普通 App 导出。Keystore 不消除运行进程内的调试风险，也不意味着普通 App 能直接解密全部凭据。

## 通知、观察台与数据

通知监听服务有系统 `BIND_NOTIFICATION_LISTENER_SERVICE` 权限保护。观察台仅绑定本机回环，但可能显示问答、通知正文和标识；诊断样本、录音及真实服务配置不随源码分发。数据去向与用户控制见[隐私说明](PRIVACY.md)。

指定凭据模式扫描未命中，只能作为相应扫描范围内的结果，不是完整秘密识别或厂商二进制安全认证。新候选目录需独立检查；厂商再分发授权和逐文件权属另见[许可范围](LICENSING.md)，不能以非商业声明替代授权。

私密漏洞反馈渠道尚待维护者设置；在渠道建立前不要把可用凭据或个人数据公开到 Issue。反馈应包含代码提交、复现步骤和脱敏证据。

Android 官方依据：[导出组件风险](https://developer.android.com/privacy-and-security/risks/android-exported)、[可调试应用风险](https://developer.android.com/privacy-and-security/risks/android-debuggable)。
