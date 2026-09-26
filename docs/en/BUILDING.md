# Build and first use: 0.2.1

[简体中文](../cn/BUILDING.md) | [Project home](../../README.md)

This builds an Android app, not a Gradle AAR. The tested environment is **Windows x64, Python 3.10+, Git, JDK 21 and Android SDK 36 build-tools**. Python and Git must be on PATH; the full offline test suite invokes Git. `bootstrap` downloads and verifies pinned JDK/Android tools but does not change PATH. Glasses connection requires Android 12+, despite the API 29 installation declaration.

The Windows build temporary directory must use an ASCII-only path. If your username or TEMP contains non-ASCII characters, set `$env:RAYNEO_BUILD_TEMP = "C:/Temp"` in the same PowerShell window before building; the tool creates the directory.

## Prepare tools and signing

From the repository root, without overwriting an existing local configuration:

```powershell
python lab.py bootstrap
Copy-Item config.example.json config.local.json
```

The example `sample` uses the bundled `vendor-payload.jar`; a separate official APK is unnecessary. `config.local.json` contains local build-tool and signing paths, not phone service credentials. See [third-party notices](THIRD_PARTY_NOTICES.md).

Only if you do not already have a signing key, create your own debug identity. Resolve keytool through the build script so a fresh bootstrap does not depend on a system keytool:

```powershell
$keytoolPath = python -c "import lab; print(lab.tool(lab.settings(), 'keytool'))"
New-Item -ItemType Directory -Force private/keys
& $keytoolPath -genkeypair -keystore private/keys/local-debug.p12 -storetype PKCS12 -storepass android -keypass android -alias research-debug -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Local Research Debug"
```

Keep the key for future upgrades. This is a local debug identity with the password/alias expected by the script, not the maintainer's signature. A different signature cannot overwrite an installed app; back up needed data before deciding whether to uninstall. Uninstalling or clearing app data deletes local recordings, records and configuration. Never commit credentials, local signing files or build output.

## Check, build and install

Before generating local files, `python verify_source.py --distribution` checks an extracted distribution. Afterwards `--worktree` checks the listed source files only.

```powershell
python lab.py doctor
python -m unittest discover -s tests
python lab.py build --profile sdk-lab
python lab.py verify
```

Use `sdk-lab`. `lab.py` chooses application ID `dev.xr.rayneo.sdklab`, while `app/lab-version.json` provides **0.2.1 / 2001**. The older identity in source AndroidManifest.xml is replaced during this build. `daily` is a retired experiment profile.

Enable USB debugging and authorize this computer. With only the target phone connected, install the new APK (add ADB `-s SERIAL` for multiple devices):

```powershell
$adbPath = python -c "import lab; print(lab.settings()['adb'])"
& $adbPath devices
$buildInfo = Get-Content out/latest-sdk-lab-build.json -Raw | ConvertFrom-Json
$apkPath = Join-Path $buildInfo.directory 'rayneo-init-probe.apk'
& $adbPath install -r $apkPath
```

Accept the phone's USB installation prompt if shown. The output directory is recorded in `out/latest-sdk-lab-build.json`. The disabled legacy `lab.py run --execute` diagnostic does not install or connect the app for you.

## First connection

Open **AIX IO SDK Lab**, select “验证 SDK 初始化（不连接眼镜）” (initialize without connecting), then “进入连接与录音验证”. In the app shell, “设置 → 眼镜与连接” opens connection controls.

End the official app's session and exit its process before connecting the same glasses. If its binding blocks pairing, unbind there, close it, then follow the glasses' pairing prompt. Our unbinding tests did not erase glasses data; later official-app/firmware behavior is unverified. Unbinding is not a routine reconnect step. Grant Bluetooth/nearby-device access and select your glasses. Authentication or a battery report alone does not establish a ready business session.

## Enable features

| Goal | App entry and minimum check |
| --- | --- |
| Local recording | “功能 → 随身录音 → 开始录音”, then “结束并保存”. Play from “记录”; “导出音频” exports a file. The default maximum is 30 minutes, configurable from 5–120, not proof of tested long-duration stability. Recording does not automatically transcribe. |
| Cloud Q&A | In “设置 → 助手服务”, open service configuration, enter your DashScope speech key plus the selected answer provider's key, full endpoint and available model, then save. In “功能 → 语音助手”, enable glasses voice standby and wake the assistant. No keys are bundled. |
| Answer provider | DeepSeek and DashScope are supported choices. Use models available to your account and the matching credentials. Some old labels still say DeepSeek; `assistant_provider` controls actual routing. |
| ASR diagnostics | Choose “选择音频上传转写” with your own nonsensitive audio. The older preset-upload button needs `asr-test.wav` on the phone, which is not included; a missing sample is not evidence of a bad key/network. Phone microphone capture remains diagnostic, not a standalone phone voice assistant. |
| Todos | Use “功能 → 我的待办”, create an item and check synchronization; mark it complete on the glasses and compare the phone. Assistant voice creation is distinct from creation in the native glasses list. |
| Weather | Open “功能 → 当地天气”, allow location and enable/refresh. Opening this page with permission already granted enables automatic weather. It calls a weather service using phone location, not the phone's weather app. Background access depends on system permission; see [privacy](PRIVACY.md). |
| Notifications | “功能 → 消息提示” or “设置 → 消息偏好”: grant notification access and select sources. Generate an actual system notification and check the glasses. |
| Optional knowledge | Configure your compatible WSS Gateway and token. No server is bundled; a regular HTTP chat endpoint is not compatible. See [contract](GATEWAY.md). |

Speech/model requests may incur charges. `llm.local.json` is for the computer-side `model_probe.py` only and does not configure the phone. Diagnostic helpers default to `sdk-lab`; use `--profile daily` only for the legacy package. See [feature limits](FEATURES-0.2.1.md), [validation](audits/2026-09-26-0.2.1.md) and [troubleshooting](TROUBLESHOOTING.md). Building successfully does not establish device acceptance on another phone/firmware.
