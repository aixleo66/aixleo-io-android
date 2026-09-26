# Developing with a coding agent: routes and pitfalls

[简体中文](../cn/DEVELOPMENT.md) · [Agent entry](../../AGENTS.md) · [Capability map](CAPABILITY-MAP.md)

You do not need to reverse-engineer the official app before building or changing this project. Start with a small change in the existing adapter/UI and ask your coding assistant to identify the actual files and tests. This repository builds an Android app with a Python pipeline, not a standalone AAR or a missing Gradle project.

## Layers and vocabulary

Phone UI/configuration calls the app's session, recorder and assistant coordination. Commands then pass through the owned adapter and pinned vendor communication dependency to glasses firmware. Cloud ASR, answers, optional knowledge and weather have separate clients. Phone send completion, device acknowledgement, first visible text and the final page are different events; firmware also retains state and handles inputs/sleep independently.

- Application ID is the installed package; sdk-lab selects dev.xr.rayneo.sdklab, while source Java can remain in dev.xr.rayneo.probe. Do not globally rename it.
- A session owns device state; a round owns one assistant input/answer. Old callbacks must not act on a newer owner.
- ASR converts speech to text; endpointing decides when speech ends; intent selects an action. Current task phrases are mostly rules, not a general intent model.
- ADB/run-as is authorized debugging access to a debuggable app, not a normal remote public interface.
- Gateway is an optional client/server contract. No server or arbitrary remote-task engine is bundled.

## Where to start

| Task | Files/classes | Verification |
| --- | --- | --- |
| UI/navigation | CloudActivity, CompanionShell, ShellNavigation | Existing page/navigation tests; preserve underlying actions. |
| Device setting | GlassesSettingWrite, RayNeo*Settings | Missing/range rejection, preserved fields and payload; authorized device write/readback/observation/restoration later. |
| Speech/follow-up | StreamingAsr, AsrPartialGate, DisplayedAnswerPolicy, SdkProbeActivity | Separate all deadlines/events; virtual-time and late-callback tests. |
| Recording | GlassesRecorder, RecordingAudio, OggOpusWriter, CloudActivity | State/file tests, then authorized actual playback; short audio does not prove long recording. |
| Todos | TodoStore, TodoGlassesClient, TodoFullSync, VoiceTodoAction | Local data, full-list sync, glasses completion and explicit voice creation separately. |
| Notifications | NotificationPolicy, NotificationSettingsUi | Real source system notification → filter → send → wearer observation. Ongoing, foreground-service-flagged and `service`-category notices are filtered on purpose; a notice with none of these must still pass the group-summary, silent and source-selection filters. Read its flags/category before changing the rule; absence of these markers does not guarantee forwarding. |
| Weather | PhoneWeather, WeatherPolicy/Sync/Retry, WeatherActivity | Permission/location, request, scheduling and delivery separately. |
| Providers/Gateway | CloudConfig, CloudClient, KnowledgeClient | Mock response, timeout, cancellation and sources before authorized real-service tests. |
| Connection/background | ConnectionService, ReconnectPolicy, SessionOwnership, SessionCommandGate | Check package, competing official process, session and permissions before resetting anything. |

See the capability map for linked source and parameters. Search exact class/command names and corresponding tests instead of loading all source into every model request.

## Pitfalls from development

1. Helpers reading an old package can appear offline or show stale data. Confirm profile, installed version and session ID.
2. Closing the official UI may leave its process connected. Disconnect and close it; unbind only as needed for pairing. Tested unbinding did not erase glasses data, but that is not a future-firmware guarantee or a reason to unbind on every reconnect.
3. Battery/authentication can arrive before the business/audio session is ready.
4. Crown writes preserve direction/double-tap/long-press together. Settings have different payload contracts: null auxiliary fields and JSON-encoded strings are not interchangeable.
5. UI ranges, code guards, accepted values and wearer effects differ. The observed 0–70° UI versus 0–90° code range is not proof of 71–90° support.
6. Speech silence, no-input timeout, transfer deadline, display completion, follow-up window and firmware sleep are separate. Do not start a reading timer at the beginning of a long transmission or invent a last-page receipt.
7. Cancellation needs session/round/recording ownership and late-event checks, not just a UI reset. It may not recall already submitted cloud data.
8. A knowledge answer is not proof of retrieval. sources events and result citations differ; long lens answers may be shortened while full text remains on the phone.
9. Recording stop, successful file save, playback decode and transcription are distinct. Preserve usable data on failure and avoid false success.
10. A new installation has no preset asr-test.wav; use file selection. Computer llm.local.json does not configure the phone. Keys, endpoints and models must belong together.
11. bootstrap installs pinned tools but does not add keytool to PATH. Follow BUILDING. Retain your debug signing key; a new signature cannot overwrite an old one, and uninstalling deletes phone app data.
12. Do not have an agent regenerate the project as Gradle or swap frameworks to make it “standard”. Verify a scoped change in the existing build pipeline first.

## Good task requests

Example: “Read AGENTS and the capability map. Clarify text on recording details, keep the recorder state machine unchanged, identify affected files and run existing checks; do not install.”

For new hardware behavior: “Find existing height-write adaptation and device evidence first. If only readback exists, describe the gap rather than guessing a command. Reuse a verified adapter and prepare offline tests before device work.”

Ask for actual changes, evidence/tests, unverified scope and Git status. A claim that a feature is supported without version and reproduction conditions is insufficient.

## Documentation and delivery

Update affected README capabilities, feature/parameter pages and both languages. Keep version-specific evidence; old tests do not validate new behavior. Exclude private keys, recordings, chat, full internal SDK documents and raw dumps; preserve third-party attribution/licenses. An edited source manifest no longer identifies the original archive: review an exact whitelist when preparing a new distribution, without rewriting historical evidence. [Troubleshooting](TROUBLESHOOTING.md) records old app labels/diagnostics that are documented, not fixed in the UI.

Some class names, test names and comments retain historical stage/review identifiers to preserve their relationship with existing tests. They are not dependencies on unpublished SDK manuals; use the current source, public capability map and tests as the basis for changes.
