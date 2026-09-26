# Device capabilities and settings reference

[简体中文](../cn/DEVICE-CAPABILITIES.md) | [English index](README.md)

This is an original user-facing summary, not official SDK documentation or a low-level protocol specification. Observations mainly come from official App 1.0.4 screens and research on the corresponding firmware. **An official control does not establish that this app can write it or that it has been validated on a device.** A selected value in a screenshot is not necessarily a factory default.

| Officially visible capability or setting | Observed range or controls | Status in this app, 0.2.1 |
| --- | --- | --- |
| Lens brightness | Slider; research records identify levels 1–17 | Manual adjustment has limited device evidence. Automatic lens brightness needs separate interpretation and must not be confused with the external indicator. |
| Display height and distance | Two independent sliders | Read-only state evidence exists; writes and full ranges remain incomplete. |
| Head-up wake | Switch, 0°–70° dial and restore-default action | Limited angle/switch round trips. Actual trigger motion depends on firmware; numeric values are not calibrated angle accuracy. |
| Screen timeout | 5/10/15/25/40/60/120 seconds | Five/ten-second write/readback and wearer observations passed. |
| Crown interaction while the screen is off | Switch | Officially visible; this app's mapping and effect remain incomplete. |
| Crown direction, double press and screen-off long press | Direction and preset actions; the system screen-on long-press action is separate | Direction reversal has wearer evidence. This is not arbitrary key remapping, and not all double/long-press actions are supported. |
| Head-gesture confirmation | Switch and nod/shake confirmation setting | Some read/write and limited comparison evidence; revalidate with other firmware. |
| Voice-assistant settings | Master switch, wake, interruption, wake words, conversation language and official models | Official cloud models differ from this app's user-configured services. Most system settings are not exposed as writable controls here. |
| Do not disturb | Master switch, exit from glasses, scheduled enablement | Officially visible; writes from this app remain incomplete. |
| Privacy and indicator | Microphone permission, external-indicator automatic brightness and levels, analytics options | Officially visible; automatic indicator brightness is separate from lens brightness. |
| Dashboard widgets | Modes, weather, calendar and other widgets | Readable fields do not establish widget installation/removal. A custom-card installation trial failed. |
| Notifications | Master switch, calls, SMS replies and source list | This app has its own source selection; its filter semantics cannot be substituted for the official app's rules. |
| Recording post-processing | Playback, marks, trimming, sharing and export formats | Local recording and some marks are implemented; not all official post-processing actions are supported. |
| Live captions and translation | Language, audio direction, audio saving and translation modes | Current caption/translation channel trials failed and are disabled; not presented as usable app features. |
| Language and region | App language, AI output language, date/time formats, temperature units and measurement units | Officially visible; these separate settings are not fully implemented here. |

Todo synchronization, the weather source, cloud recognition and answer routing in this app are independent implementation choices. Using the glasses for display and input does not make them official features. Before exposing a writable setting, establish separate evidence for its field, command, readback and visible effect.

## Using this inventory for development

See the [developer capability map](CAPABILITY-MAP.md) for parameters supported by the supplied code, readable fields and source locations. “Officially observed range” means the captured UI range; “code-accepted range” means adapter validation. They can differ: the observed head-up UI spans 0–70°, while this code accepts 0–90°. Values 71–90° do not have per-value device evidence and must not be treated as verified capabilities.

Numeric claims are identified as screen observations or code constraints. Device checks mainly come from development records such as Strix OS 1.0.4.12; this does not mean every parameter was tested at every value on that firmware. Crown/head-gesture evidence covers only some values; others remain extension leads.
