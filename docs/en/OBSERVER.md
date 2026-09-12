[简体中文](../cn/OBSERVER.md) | [English](OBSERVER.md)

# Browser display observer

The observer reads this app's runtime state and redraws it in a browser. It is not a lens screenshot or actual screen mirroring. A completed send does not prove that the wearer saw the content.

Follow [build and setup](BUILDING.md) to configure Python and ADB, build/install the 0.13 debug APK, authorize this computer for USB debugging, and start a connection from the phone app. Keep the app session running. No APK is included in this repository, and `lab.py run --execute` does not launch the non-exported diagnostic Activities. Use the configured ADB's `adb devices` command to obtain your phone's serial number. From the repository root, run:

```powershell
python display_observer.py --serial "YOUR_PHONE_SERIAL" --port 8791 --seconds 1800
```

Open `http://127.0.0.1:8791/`. The server binds only to loopback and strictly validates Host. It provides no remote-control or BLE-write interface. Once per second it uses ADB `run-as` to read this app's `files/result.json` and checks whether the process is still alive. An ordinary non-debuggable production APK may not permit this access.

The default lifetime is 30 minutes. Stop it earlier with Ctrl+C. Stopping the observer does not stop the phone app, glasses standby, or an active recording; use the app for those controls. If the port is occupied, choose another port and use the matching URL.

Only bounded current state and stages are shown. This is not a complete history, and it does not automatically produce a report for an entire testing session. Content is not redacted by default. Check questions, answers, notifications, addresses, and other identifiers before showing or sharing it. Do not directly commit screenshots or exported local results to a public repository.

Success means the browser is online, the session/process is live, and the relevant stages update after an explicit action. Lens display still requires wearer confirmation. Record protocol results and wearer observations separately in [test records](TESTING.md). If the page is offline, first check USB authorization, ADB configuration, the app process, and the observer lifetime; do not repeatedly unbind the glasses merely to restore this page.
