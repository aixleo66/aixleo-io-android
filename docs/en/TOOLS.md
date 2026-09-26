# Command-line tools

[简体中文](../cn/TOOLS.md) · [Building](BUILDING.md)

Use the phone UI normally. These tools are developer diagnostics; inspect `python TOOL.py --help` first. Local configuration and output are not public artifacts.

| Tool | Purpose | Effects |
| --- | --- | --- |
| lab.py | bootstrap, doctor, build, verify | bootstrap downloads tools; build uses your signing key. Legacy run --execute is disabled. |
| verify_source.py | Distribution/worktree/Git hash and file checks | File verification, not a device test. |
| display_observer.py | Redraw an existing session in a localhost browser | ADB read-only, default 30 minutes; no glasses control. Manual confirmations in out/visual-confirmations are human records, not automatic lens evidence. |
| idle_check.py | Time-limited sampling of an existing session | ADB read-only; no Q&A initiation; output includes session data. |
| session.py | Query or send explicit commands to an existing session | Send actions can operate notifications, recording, standby and settings. Check help and the command whitelist; this is not side-effect-free. |
| model_probe.py | One short computer-side model request and optional notification delivery | Without --execute it checks configuration only. With --execute it contacts your endpoint and may incur charges. Delivery requires --send-to-glasses --use-session plus serial. It is not the phone's complete speech pipeline. |

Observer/idle/model helpers default to sdk-lab; --profile daily selects the old package. Check session.py's profile explicitly as well. Do not run competing device writers.

Copy llm.example.json to llm.local.json if no local file exists; fill your own endpoint/model/api_key. Check configuration without a model request:

```powershell
python model_probe.py --config llm.local.json
```

Only add --execute and --prompt to make a real request after checking the service. Questions and answers are written under out/model-runs; do not publish them. Legacy non-session delivery is rejected; establish a phone-app session before using --use-session as described in help.
