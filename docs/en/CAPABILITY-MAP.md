# Developer capability map and parameter entry points (0.2.1)

[简体中文](../cn/CAPABILITY-MAP.md) · [Feature limits](FEATURES-0.2.1.md)

This map is derived from the bundled implementation, not a complete vendor SDK specification or standalone AAR. Most classes are package-private and depend on app session/lifecycle handling. The communication dependency is pinned to official app 1.0.2 (68), while some settings adapters were studied against 1.0.4. Firmware compatibility and parameter semantics require separate verification.

## Implemented development entry points

Implementation, offline tests, transport completion, device readback and wearer observation are distinct evidence levels. Device coverage is described in the feature matrix.

| Capability | Reusable implementation | Source |
| --- | --- | --- |
| Connection/session | Paired-device connection, authentication, readiness, command exclusion and reconnect handling | [SdkProbeActivity](../../app/src/SdkProbeActivity.java), [SessionOwnership](../../app/src/SessionOwnership.java), [SessionCommandGate](../../app/src/SessionCommandGate.java) |
| Events/status | Observed scalar fields; a battery reply does not establish business readiness | [ReportedStatusPolicy](../../app/src/ReportedStatusPolicy.java), [ReportedSettingsPolicy](../../app/src/ReportedSettingsPolicy.java) |
| Voice/answers | Glasses audio, cloud ASR, configurable answer provider, native display, cancellation and stale-turn isolation | [StreamingAsr](../../app/src/StreamingAsr.java), [CloudConfig](../../app/src/CloudConfig.java), [SdkProbeActivity](../../app/src/SdkProbeActivity.java) |
| Follow-up/context | Wake gates and windows; memory-only history bounded by six turns and 4000 Java characters, trimming/rejecting oversized entries | [VoiceWakePolicy](../../app/src/VoiceWakePolicy.java), [DisplayedAnswerPolicy](../../app/src/DisplayedAnswerPolicy.java), [AssistantConversation](../../app/src/AssistantConversation.java) |
| Recording | Native/phone initiation, local Ogg Opus, marks and playback | [RecordingAudio](../../app/src/RecordingAudio.java), [OggOpusWriter](../../app/src/OggOpusWriter.java), [RecordingMarkStore](../../app/src/RecordingMarkStore.java), [CloudActivity](../../app/src/CloudActivity.java) |
| Todos | Phone full-list synchronization, glasses completion events, explicit voice creation on the phone then sync back | [TodoGlassesClient](../../app/src/TodoGlassesClient.java), [TodoFullSync](../../app/src/TodoFullSync.java), [TodoStore](../../app/src/TodoStore.java), [VoiceTodoAction](../../app/src/VoiceTodoAction.java) |
| Weather/time | Phone location → Open-Meteo → glasses payload; session-ready and roughly 15-minute refresh scheduling; phone clock sync | [PhoneWeather](../../app/src/PhoneWeather.java), [WeatherSync](../../app/src/WeatherSync.java), [WeatherPolicy](../../app/src/WeatherPolicy.java), [SdkProbeActivity](../../app/src/SdkProbeActivity.java) |
| Notifications | System-notification source selection/filtering; 0.2.1 suppresses foreground-service persistent notices (new check: flag `FLAG_FOREGROUND_SERVICE` or category `service`; notices not matched by this new check still pass through existing ongoing, group-summary, silent and source-selection filters; forwarding is not automatic) | [NotificationPolicy](../../app/src/NotificationPolicy.java), [NotificationSettingsUi](../../app/src/NotificationSettingsUi.java) |
| Battery/case | Battery and case-related event observation; do not infer continuous charging refresh or unverified enum semantics | [SdkProbeActivity](../../app/src/SdkProbeActivity.java), [ReportedStatusPolicy](../../app/src/ReportedStatusPolicy.java) |
| Settings | Write builders, comparisons and limited restorable trials | [GlassesSettingWrite](../../app/src/GlassesSettingWrite.java), [RayNeoCrownSettings](../../app/src/RayNeoCrownSettings.java), [RayNeoHeadSettings](../../app/src/RayNeoHeadSettings.java), [RayNeoWakeSettings](../../app/src/RayNeoWakeSettings.java) |
| Optional knowledge | Structured WSS answers/sources, resumption and deadline; no bundled server or arbitrary remote control | [KnowledgeClient](../../app/src/KnowledgeClient.java), [KnowledgeRunState](../../app/src/KnowledgeRunState.java), [contract](GATEWAY.md) |

## Settings implemented in the write builder

These are current code constraints, not guaranteed vendor ranges. Read a fresh complete snapshot, change only the requested field and preserve the others; missing fields must not become guessed zeros.

| Setting | Parameters and message | Evidence/limits |
| --- | --- | --- |
| Manual lens brightness | `brightness.value` integer 1–17; UI maps to 0–100%; `brightness_change`, message type 2 | Limited level/lens checks. UI 0% means level 1, not screen-off. The external indicator is separate. |
| Auto sleep | `auto_lock.value`: 5/10/15/25/40/60/120 seconds; `auto_lock_time` explicitly uses null mode/data | Five/ten-second write/readback/wearer checks; independent of assistant follow-up and answer-exit timers. |
| Crown | `direction`: 0 standard/1 natural; preserve/send `double` and `longPress` together; `crown_config.data` is a JSON **string** | Direction reversal observed. Builder allows actions 0–9, excluding 6 for screen-off long press; not all actions have device acceptance. Screen-on long press is not arbitrary remapping. |
| Head gesture | `enabled` 0/1; `mode` 0 nod-confirm/1 shake-confirm; `head_gestures` carries direction in mode | Preserve the enable switch when changing direction; device evidence varies. |
| Head-up wake | Send `headup_switch`, `headup_degree`, `crown_switch` together in `wakeup_config.data`, encoded as a JSON string | Code accepts 0/1, integer 0–90 degrees and 0–3 respectively. The captured official angle UI showed 0–70°, so 71–90° is **not established as usable**. Five/sixty-degree observations establish different behavior, not calibrated precision. crown_switch is not a boolean. |

Other setting writes use message type 5 in this implementation. `Lab*Trial` classes temporarily change and restore values; normal product writes persist. Do not confuse these paths.

## Readable clues without a complete write contract

| Group | Fields retained | Limits |
| --- | --- | --- |
| Display | `generalSettings.displayConfig.height/distance` | Readback does not establish editable height/distance. |
| Privacy/indicator | `generalSettings.privacyConfig.mic_switch/led_light/led_auto_light/log_switch` | Indicator auto brightness is not lens auto brightness; no complete independent write loop. |
| Storage | `generalSettings.storageTotal/storageUsed` | Not a file-cleanup/export API; verify units against device evidence. |
| Status | `battery`, `brightness`, `automaticBrightness`, `batt_temp`, `isCharging`, `chargeType`, `screenStatus`, `hallStatus`, `micStatus`, `focusMode` | Only selected finite numeric/boolean values are retained; enum meanings and presence are not assumed. |
| DND structure | `enable`, `enableGlassClose`, `policy.auto/type/begin/end/weekday` when numeric/boolean | Structural clues, not a working schedule editor; strings/arrays are not numeric fields. |

Readback uses camelCase fields such as headupSwitch, while write builders use names such as headup_switch. Never send the readback snapshot unchanged as a write payload. Status and settings use different channels and must respect the session command queue.

## Official-only or unfinished directions

See [official UI capability reference](DEVICE-CAPABILITIES.md) for subtitles/translation, dashboard installation, wake-word settings, vendor cloud models, reminders/account sync, regional units and recording post-processing. These are research directions, not usable app features. Translation/subtitle trials are disabled; a historical screen is not evidence of a working channel.

For extensions, record adapter behavior, offline payload/state checks, device receipts, then lens/audio observation. Public reports should contain redacted reproduction steps, not internal SDK documents, APK dumps or personal raw logs.
