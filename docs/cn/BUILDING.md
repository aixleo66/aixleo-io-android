# 0.2.1 自行构建与首次使用

[English](../en/BUILDING.md) | [项目首页](../../README.md)

本工程构建 Android App，不是 Gradle AAR。已验证环境为 **Windows x64、Python 3.10+、Git、JDK 21 和 Android SDK 36 build-tools**。Python 和 Git 需在 PATH；完整离线测试会调用 Git。`bootstrap` 联网下载并校验固定 JDK/Android 工具，不会修改系统 PATH。眼镜连接要求 Android 12+，安装声明的 API 29 不代表 Android 10/11 连接已支持。

Windows 构建临时目录必须是纯 ASCII 路径；若用户名或 TEMP 含中文，在同一个 PowerShell 窗口先设置 `$env:RAYNEO_BUILD_TEMP = "C:/Temp"` 再构建。工具会创建该目录。

## 1. 准备工具与本机签名

在仓库根目录运行。已有本机配置时不要覆盖：

```powershell
python lab.py bootstrap
Copy-Item config.example.json config.local.json
```

示例 `sample` 指向随包 `vendor-payload.jar`，无需另找官方 APK。`config.local.json` 用于本机 JDK、build-tools、`android.jar`、ADB 及签名路径；不是手机的云服务配置。厂商依赖来源见[第三方说明](THIRD_PARTY_NOTICES.md)。

仅在尚无自己的签名时创建。通过脚本定位 keytool，避免刚 bootstrap 后系统找不到命令：

```powershell
$keytoolPath = python -c "import lab; print(lab.tool(lab.settings(), 'keytool'))"
New-Item -ItemType Directory -Force private/keys
& $keytoolPath -genkeypair -keystore private/keys/local-debug.p12 -storetype PKCS12 -storepass android -keypass android -alias research-debug -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Local Research Debug"
```

这是脚本使用的本地调试身份与示例口令，不是维护者签名。保留这份密钥以便升级自己构建的 App。不同签名无法直接覆盖安装；先备份需要的数据再决定是否卸载，卸载或清除应用数据会删除手机端记录与配置。配置、签名和构建输出不要提交。

## 2. 验证并构建

刚解包、还未生成本地文件时可先运行 `python verify_source.py --distribution`；已有工具和配置后用 `--worktree` 核对清单内文件。

```powershell
python lab.py doctor
python -m unittest discover -s tests
python lab.py build --profile sdk-lab
python lab.py verify
```

正常构建配置为 `sdk-lab`，应用 ID 是 `dev.xr.rayneo.sdklab`，由 `lab.py` 控制；版本由 `app/lab-version.json` 注入，为 **0.2.1 / 2001**。源 `AndroidManifest.xml` 中保留的旧实验身份会在构建时替换。`daily` 是旧实验配置，不是本版入口。

打开手机 USB 调试并授权此电脑；仅连接目标手机后安装刚生成的包（多设备时给 ADB 加 `-s 序列号`）：

```powershell
$adbPath = python -c "import lab; print(lab.settings()['adb'])"
& $adbPath devices
$buildInfo = Get-Content out/latest-sdk-lab-build.json -Raw | ConvertFrom-Json
$apkPath = Join-Path $buildInfo.directory 'rayneo-init-probe.apk'
& $adbPath install -r $apkPath
```

输出目录以 `out/latest-sdk-lab-build.json` 为准。手机若有 USB 安装确认需允许。`lab.py run --execute` 的旧诊断路径不能替代安装和手机内连接。

## 3. 首次打开与连接

打开 **AIX IO SDK Lab**，先点“验证 SDK 初始化（不连接眼镜）”，再进入“连接与录音验证”。主界面从“设置 → 眼镜与连接”查看和建立连接。

先正常结束官方 App 的设备会话并退出其进程，避免两个客户端争用。若绑定在官方一侧导致新客户端无法配对，可在官方端解除绑定后退出，再按眼镜提示重新配对。本项目的解绑实测未观察到眼镜数据清空；后续官方 App 或固件行为未验证。**日常重连不需反复解绑。** 按 Android 提示授权蓝牙/附近设备并选择自己的眼镜；仅认证或电量回报不代表业务会话就绪。

## 4. 开通各项能力

| 目标 | 手机入口与最小检查 |
| --- | --- |
| 本地录音 | “功能 → 随身录音 → 开始录音”，说几句话后“结束并保存”；到“记录”播放，可在详情“导出音频”。默认最长 30 分钟，可设 5–120 分钟；这是限制配置，不是长录音实测保证。普通录音不会自动转写。 |
| 云端问答 | “设置 → 助手服务”进入服务配置，填自己的 DashScope 识别 Key，以及所选回答服务的 Key、完整接口地址和模型名，保存；到“功能 → 语音助手”开启眼镜语音待命，再唤醒提问。Key 不随源码提供。 |
| 回答服务选择 | 回答服务支持 DeepSeek 或 DashScope，所填模型须由自己的账号实际可用；不能把服务 Key 混用。部分旧界面文字仍写 DeepSeek，实际以 `assistant_provider` 配置为准。 |
| 识别单独排查 | 用“选择音频上传转写”选自己的非敏感音频。旧“上传预置语音”诊断依赖手机里的 `asr-test.wav`，此包未附该文件；缺文件不代表 Key 或网络错误。手机麦克风入口仍是诊断，不是正式的脱离眼镜语音助手。 |
| 待办 | “功能 → 我的待办”新建一条，查看同步结果；在眼镜原生列表勾完成后对照手机。眼镜助手明确口令创建与原生列表直接新增是不同能力。 |
| 天气 | “功能 → 当地天气”，授予定位后启用/刷新；已有定位权限时打开页面会启用自动天气。通过手机定位调用网络天气服务，不读取手机自带天气 App。持续后台定位需系统允许，见[隐私](PRIVACY.md)。 |
| 通知 | “功能 → 消息提示”或“设置 → 消息偏好”，授权系统通知使用权并选来源；让该来源真正产生一条手机系统通知，再看镜片。 |
| 可选知识库 | 助手服务中填自己的兼容 WSS Gateway 和 Token。仓库没有服务端，不能把一般 HTTP 聊天地址填在这里；见[契约](GATEWAY.md)。 |

识别/回答会访问你配置的服务并可能计费。`llm.local.json` 只供电脑端 `model_probe.py` 使用，不会自动配置手机；日常手机问答从 App 服务设置配置。诊断工具默认读取 `sdk-lab`，只有调试旧包才指定 `--profile daily`。

实际覆盖见[功能与边界](FEATURES-0.2.1.md)、[构建测试记录](audits/2026-09-26-0.2.1.md)及[排查指南](TROUBLESHOOTING.md)。完成本机构建不等于在另一款手机/固件完成设备验收。
