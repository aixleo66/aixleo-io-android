[简体中文](BUILDING.md) | [English](../en/BUILDING.md)

# 安装、配置与研究构建

源码包含固定厂商 payload，当前不附预编译 APK。先按下方“自行构建”生成自己的签名 APK，再进行安装和配置。本文随当前分支维护；请阅读与所选代码提交对应的文档。构建仍保留调试能力，已完成的验证及待办统一记录在[审核索引](AUDIT.md)。

手机 App 界面目前仍使用中文；英文文档保留实际中文按钮名并提供英文释义。文档翻译不代表 App 界面或 ASR 语言配置已完成英文化。以下命令均在仓库根目录执行，不在文档目录执行。

## 自行构建后的首次安装与配置

1. 完成下方构建后，从 `out/latest-build.json` 找到本次生成的 APK，自行安装到测试手机，再点击手机桌面的“雷鸟随身助手”启动。遇签名冲突先备份、核实来源，不要为了覆盖安装直接删除已有数据；不要用其他版本的安装包代替本次构建。
2. 首先进入「设备 → 模型与语音服务」，填写自己的「眼镜蓝牙地址」并保存。该地址默认空白，当前 App 没有扫描后点选设备的界面；需要先从自己设备的信息或已核实的调试记录取得正确地址，使用大写十六进制冒号格式。不要填写手机蓝牙地址或照抄别人的示例。未取得地址时，当前版本不能完成首次连接。
3. 返回设备页授予蓝牙权限并连接自己的眼镜；按系统提示完成配对。若提示需要首次系统配对，可到「设备 → 开发者诊断 → 完成系统配对（首次使用）」。从官方 App 切换时处理原有绑定；官方解绑可能清空眼镜数据，日常重连不要反复恢复出厂。看到“业务连接已认证”后再测试录音。
4. 先测试本地录音、结束保存及播放。语音助手另需在服务配置页填写自己的 ASR、模型 Key，默认值为空；检查模型名与自己的服务配置相符。要复现逐步出字，必须打开「实时识别，边说边显示提问」并保存：新安装该开关默认为关闭，关闭时走旧批处理流程。回到助手页开启待命，等“已待命”后唤醒。
5. 通知转发需授予系统通知使用权，打开转发总开关并选择应用。「仅监听检查」新安装默认开启，此时不会转发到眼镜；先核对手机提醒正常，再关闭「仅监听检查」，让选中的应用收到一条新通知，分别检查手机与眼镜。不要为了眼镜转发去关闭手机正常提醒。
6. 远端知识库是可选实验功能，尚未在本版充分验证其可用性与稳定性，可以跳过。现有 Android 适配沿用最初为 Rokid 设计的 `rokid-harness.v1` 协议，是连接本地 Codex 的一种探索方式；本版不包含 Rokid Harness 工程、服务端或维护者的可用服务。自行研究时需另备兼容 WSS Gateway 并填写 Token，不能填任意模型 HTTP 地址代替，见 [来源、范围与协议边界](GATEWAY.md)。不用知识库时保持「语音与默认文字提问使用知识库」关闭，使用配置的 DeepSeek。

「验证阿里云 ASR（上传预置语音）」依赖原开发环境预先放入的音频，新安装没有该文件，点击会失败；请改用「选择音频上传转写」并主动选择自己有权上传的测试音频。它会上传音频并可能计费。随包未提供任何私人测试样本，历史电脑脚本 stream_smoke.py 也不再包含。

完整复现步骤见 [TESTING](TESTING.md)，浏览器工具启动见 [OBSERVER](OBSERVER.md)。诊断入口修复及仍保留的调试边界见 [SECURITY](SECURITY.md)。

## 自行构建

当前以 Python 调度 javac/D8，尚无 Gradle library/AAR。Windows x64 为已验证环境，Python 3.10+、JDK 21、Android SDK 36。

从源码仓库根目录执行：

```powershell
python lab.py bootstrap
Copy-Item config.example.json config.local.json
```

bootstrap 从锁定的工具下载地址准备工具链并校验哈希，不读取私人 NAS。已有工具可通过配置指定 java_home、build_tools、android_jar、adb。下载涉及各工具供应商的许可。

仅在自己的 `private/keys/local-debug.p12` 不存在时，创建本地调试签名，不覆盖已有签名：

```powershell
New-Item -ItemType Directory -Force private/keys
keytool -genkeypair -keystore private/keys/local-debug.p12 -storetype PKCS12 -storepass android -keypass android -alias research-debug -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Local Research Debug"
```

keytool 不在 PATH 时使用所配置 JDK 的 bin/keytool.exe。bootstrap JDK 路径见工具链清单。android 是本地调试密码约定，不是维护者提供的秘密或生产签名方案；私钥不随包提供。

```powershell
python lab.py doctor
python lab.py build
python -m unittest discover -s tests
```

配置示例指向随包 vendor payload；脚本只接受固定官方样本或固定 payload 的哈希。输出在 out/builds，out/latest-build.json 指示最近构建。自己签名的 APK 不一定能覆盖已安装包。

诊断 Activity 不对外导出。`lab.py run --execute` 会在安装或操作手机前拒绝此路径；不要重新导出组件来绕过检查。请自行安装并从手机 App 启动连接。当前仍是 debug 构建，经用户授权的 ADB `run-as` 状态读取和现有会话命令工具仍可使用；启动诊断 Activity 与向已存在会话发送命令是不同路径。

这套单测不调用云或真实眼镜，部分测试需 JDK/Android 编译环境；纯逻辑通过不替代镜片确认。构建、自动测试和真机验证必须对应具体提交及产物哈希，不能沿用其他产物的验收结论。实际结果与尚未覆盖的环境见[审核索引](AUDIT.md)，回归步骤见[测试指南](TESTING.md)。
