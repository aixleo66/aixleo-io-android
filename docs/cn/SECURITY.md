[简体中文](../cn/SECURITY.md) | [English](../en/SECURITY.md)

# 安全机制与使用边界

这里介绍诊断入口、会话隔离、调试访问和数据保护。具体缺陷与修复状态见[测试与审核记录](AUDIT.md)。

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

凭据扫描的范围与结果记录在[审核记录](AUDIT.md)中。这类规则扫描不能保证发现所有秘密，也不构成厂商二进制的完整安全审计。

目前没有专用的私密漏洞反馈渠道。请先通过已有的维护者联系方式沟通，或在 Issue 中说明问题类型；不要公开可用凭据、个人数据或敏感利用细节。

Android 官方依据：[导出组件风险](https://developer.android.com/privacy-and-security/risks/android-exported)、[可调试应用风险](https://developer.android.com/privacy-and-security/risks/android-debuggable)。
