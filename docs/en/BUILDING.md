[简体中文](../cn/BUILDING.md) | [English](BUILDING.md)

# Installation, configuration, and research builds

The source includes the pinned vendor payload; no prebuilt APK is currently included. Build and sign your APK before installation and setup. This guide follows the current branch; read documentation from the same commit as your code. Debug capabilities remain enabled. Completed checks and pending work are tracked in the [audit index](AUDIT.md).

The phone UI currently uses Chinese labels. English explanations below retain the actual Chinese labels so you can find the controls. Documentation translation does not imply that the app UI or ASR language configuration has been localized. Run all commands from the repository root, not this documentation directory.

Glasses connectivity on the test phone requires **Android 12+ / API 31**. The Manifest's API 29 is only an installation minimum; current connection initialization rejects Android 10/11. APIs 31–35 lack full device validation, while historical device records use API 36. See [compatibility](COMPATIBILITY.md).

## First installation and configuration after building

1. Complete the build below. Find the resulting APK through `out/latest-build.json`, install it on your test phone, and launch “雷鸟随身助手” from the home screen. If signatures conflict, back up and verify the source first; do not delete existing data just to overwrite the installation. Do not substitute an APK from another version.
2. Open **设备 → 模型与语音服务** (Device → Model and voice services). Enter your **眼镜蓝牙地址** (glasses Bluetooth address) and save. It is initially blank; there is currently no scan-and-select device list. Obtain the verified address from your own device information or diagnostic records, using uppercase hexadecimal pairs separated by colons. Do not enter the phone's address or copy somebody else's example. First connection cannot complete without this address.
3. Return to Device, grant Bluetooth permissions, and connect your glasses. Follow Android's pairing prompt. If initial system pairing is needed, use **设备 → 开发者诊断 → 完成系统配对（首次使用）**. Resolve any binding to the official app when switching clients. Official unbinding may erase glasses data; do not repeatedly factory-reset for ordinary reconnection. Wait for **业务连接已认证** (business connection authenticated) before testing recording.
4. Test local recording, saving, and playback first. For the voice assistant, enter your own ASR/model keys in the service page; defaults are empty. Verify the model names for your services. Enable and save **实时识别，边说边显示提问** (real-time recognition; show the question while speaking) to get incremental text. It is off on a fresh install; off uses the older batch flow. Enable standby on the Assistant page and wait for **已待命** before waking the glasses.
5. For notifications, grant Android notification access, enable the forwarding master switch, and select apps. **仅监听检查** (observe-only check) is on by default and prevents forwarding. First verify that phone alerts still work, then turn observe-only off and receive a new notification from a selected app. Check both the phone and glasses. Do not disable normal phone alerts to enable glasses forwarding.
6. Remote knowledge Q&A is optional and experimental; its usability and stability have not been sufficiently validated for this version. The Android adapter uses `rokid-harness.v1`, originally designed for Rokid, as one way to connect to local Codex. No Rokid Harness project, server, or maintainer service is included. Independent experiments need a compatible WSS Gateway and token; an arbitrary model HTTP endpoint will not work. See [origin, scope, and protocol](GATEWAY.md). Otherwise leave **语音与默认文字提问使用知识库** (use the knowledge service for voice and default text questions) off and use your configured DeepSeek service.

The **验证阿里云 ASR（上传预置语音）** button requires an audio fixture from the original development environment. A fresh install does not have it and the operation fails. Instead use **选择音频上传转写** (select audio to upload for transcription), explicitly choosing a test file you are entitled to upload. Uploading may incur charges. No private samples or historical computer-side `stream_smoke.py` script are included.

See [tests](TESTING.md), [observer](OBSERVER.md), and [diagnostic isolation and remaining debug limits](SECURITY.md).

## Build your own APK

Python currently orchestrates javac/D8. There is no Gradle library/AAR. Windows x64 is the validated build environment, with Python 3.10+, JDK 21, and Android SDK 36.

```powershell
python lab.py bootstrap
Copy-Item config.example.json config.local.json
```

Bootstrap downloads the pinned toolchain and verifies hashes. It does not read a private NAS. Existing tools can be specified with `java_home`, `build_tools`, `android_jar`, and `adb`. Tool downloads remain subject to their providers' licenses.

Create a local debug key **only if** `private/keys/local-debug.p12` does not already exist. Do not overwrite an existing key.

```powershell
New-Item -ItemType Directory -Force private/keys
keytool -genkeypair -keystore private/keys/local-debug.p12 -storetype PKCS12 -storepass android -keypass android -alias research-debug -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Local Research Debug"
```

If `keytool` is not on PATH, use `bin/keytool.exe` from the configured JDK. The bootstrap JDK location is in the toolchain manifest. `android` is a local debug-password convention, not a maintainer secret or production-signing scheme. No private signing key is distributed.

```powershell
python lab.py doctor
python lab.py build
python -m unittest discover -s tests
```

The example configuration points to the included vendor payload. The build accepts only the pinned official sample or pinned payload hash. Builds go into `out/builds`; `out/latest-build.json` identifies the latest one. An APK signed with your own key may not overwrite a differently signed installation.

Diagnostic Activities are not exported. `lab.py run --execute` rejects that path before installing or operating on the phone. Do not re-export components to bypass this check. Install and connect through the phone app. The debug build still permits user-authorized ADB `run-as` state reads and commands for an existing session. Launching a diagnostic Activity and sending a command to an existing session are different paths.

Tests do not call cloud services or real glasses; some need the JDK/Android compilation environment. Logic tests do not replace lens confirmation. Builds, automated tests, and device results must identify the specific commit and artifact hash; acceptance of a different artifact cannot be carried forward. See the [audit index](AUDIT.md) for actual results and uncovered environments, and the [test guide](TESTING.md) for regression steps.
