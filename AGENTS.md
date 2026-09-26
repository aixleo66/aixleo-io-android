# Coding-agent guide / AI 开发入口

This file is for Codex, WorkBuddy and other coding assistants working in this repository. If your tool does not load AGENTS.md automatically, read it explicitly before making changes. No special agent, paid model or private maintainer environment is required.

这是雷鸟 iO 的非官方 Android 研究 App 源码，尚不是稳定的独立 SDK/AAR。无需先逆向官方 App 才能构建或修改本项目；优先使用已有适配和公开能力清单。

## Read in this order / 阅读顺序

1. [README](README.md)：当前版本、可用功能和基本边界。
2. [构建与首次使用](docs/cn/BUILDING.md) / [Building](docs/en/BUILDING.md)：工具链、本机签名、安装和服务配置。
3. [开发指南与踩坑](docs/cn/DEVELOPMENT.md) / [Development guide](docs/en/DEVELOPMENT.md)：按任务定位、架构、容易误改的地方。
4. [能力与参数入口](docs/cn/CAPABILITY-MAP.md) / [Capability map](docs/en/CAPABILITY-MAP.md)：已有源码、写入约束、只读字段及未实现方向。
5. [功能证据](docs/cn/FEATURES-0.2.1.md) / [Feature evidence](docs/en/FEATURES-0.2.1.md)、[兼容性](docs/cn/COMPATIBILITY.md) / [Compatibility](docs/en/COMPATIBILITY.md)、[测试](docs/cn/TESTING.md) / [Testing](docs/en/TESTING.md)：不要把实现、离线测试、设备回包与镜片观察混为一谈。 Distinguish implementation, offline tests, device responses and wearer observations.

## Work rules / 修改规则

- Inspect the actual checkout, branch, diff and requested scope first. Preserve existing user changes. In a source archive without Git, retain an original copy for comparison. Do not treat the directory name as the app version.
- Default build profile is **sdk-lab**, application ID `dev.xr.rayneo.sdklab`. `app/lab-version.json` controls version; `lab.py` selects the package/profile. `daily`/`dev.xr.rayneo.probe` is legacy. Do not globally replace the Java package: source namespace and installed application ID intentionally differ.
- Follow the Python build pipeline; do not assume Gradle files are missing and regenerate the project. See BUILDING for JDK/Android tools, Git prerequisite and local debug signing.
- Keep vendor payloads and pinned dependency hashes unchanged unless the task explicitly concerns an upgrade. Do not edit decompiled vendor content to repair an app-level bug. Respect bundled licenses and attribution.
- Use existing session ownership, command gate and round/recording IDs. Do not bypass readiness merely because authentication or battery arrived, guess unknown enum values, clear buffers blindly, or send concurrent setting writes.
- Keep real credentials in local/device configuration. Never paste keys, recording bodies, notification text, Bluetooth addresses or local service tokens into public code, docs, logs or issues. Synthetic fixtures are appropriate for tests.
- Installation, pairing/unbinding, firmware actions, cloud requests and device writes have real effects. Perform them only within the user's authorized task; document what was actually run. Do not use repeated reinstall/unbind as a substitute for diagnosis.
- For source changes, run relevant tests, then the full suite when shared behavior changes: `python -m unittest discover -s tests`. Tools must already be prepared. One optional real-audio test may skip; report why. Tests do not establish physical-device success.
- Check documentation in both languages, local links, CLI examples and capability claims. See [文档约定](docs/cn/DOCUMENTATION.md) / [Documentation](docs/en/DOCUMENTATION.md). Update only affected claims, without importing internal work logs.
- The distributed hash manifest describes the supplied source bytes. After local edits, a mismatch is expected; do not regenerate it just to hide unexpected differences. For an authorized new distribution, review the exact file set before rebuilding its manifest. See [release checklist](docs/cn/RELEASE-CHECKLIST.md).
- Finish with files changed, behavior changed, tests actually run, remaining limits and Git status. Commit/push/release only within the user's authorization; local files are not a published release.

## Useful starting commands

```powershell
git status --short --branch
git diff --stat
python lab.py --help
python verify_source.py --distribution
```

The first two need a Git checkout. Distribution verification is for a clean source archive, before generating local files; use `--worktree` later. These commands do not connect glasses or call a model. See [CLI tools](docs/en/TOOLS.md) / [工具说明](docs/cn/TOOLS.md) before running device or cloud commands.
