# Connection and usage troubleshooting

[简体中文](../cn/TROUBLESHOOTING.md) | [English index](README.md)

These are known research-app limits and a diagnostic order. Check the phone app state before interpreting lens behavior. A send callback or battery report alone does not establish a ready business session.

## Connection stalls after switching from the official app

Two clients competing for the same glasses can stall discovery, authentication or connection. Disconnect normally in the official app first. If it retains the connection in the background, terminate its process before connecting this app. Ending the official process restored connection in recorded tests, but it is not the proven cause of every failure. **Unbinding tests did not clear glasses data; behavior after later official-app or firmware updates is unverified. Do not use unbinding as a routine reconnect step.** Follow the actual glasses and Android prompts when pairing again for the first time.

## Authentication or battery is visible, but voice does not work

Distinguish the Bluetooth link, authentication, business-session readiness and voice standby. Authentication/battery can arrive while pairing confirmation, the voice channel or service recovery is still pending. Do not repeatedly issue recording or settings commands; confirm readiness and standby in the app first.

## The phone has a message but the glasses do not

First check that the message actually appeared in Android's notification shade. A source app in the foreground may not post a system notification, leaving nothing to forward. Then check notification-listener permission, selected source apps, the glasses connection and lens visibility. Version 0.2.1 ignores persistent notices with the foreground-service flag or category `service`; useful service notifications can also be filtered.

Conversely, one real-device “SMS is running” notice was still forwarded. Its flags/category were not captured and the cause remains unconfirmed; do not infer markers from its visible text alone. Existing ongoing, group-summary and silent filters also remain active. Inspect the app's notification channels in Android settings. Disable a persistent-notice channel only when it can be separated from real messages, then confirm normal messages still arrive. This workaround has user-reported success, not validation across all source apps.

## Temperature is empty or does not refresh immediately after reconnecting

Weather comes from phone location and a network weather service, not the phone's built-in weather app. Check location permission, network access, background execution conditions and session readiness. Battery-saving policies affect scheduling; precise refresh intervals are not guaranteed. Existing lens weather is not necessarily cleared on disconnection.

## A complete recording cannot be found after stopping

Wait for trailing audio packets and saving to complete; a sent stop command does not prove a saved file. Check the recording list's save state and the actual playable file. Ordinary recordings do not automatically transcribe. Treat incomplete fragments and unfinished receipts separately; do not repeatedly stop or blindly overwrite originals.

## Case battery or state stops updating

In-case, lid and case-battery reports are separate events. Continuous battery refresh during charging remains unverified. An unchanged old value alone does not prove disconnection; check current connection and case state together.

Remove Bluetooth addresses, serial numbers, recordings, notification bodies, conversation text, location and credentials from public issues/logs. If lens observation matters, describe what was actually visible; a computer-side successful-send callback is not sufficient.

## Legacy diagnostics and UI wording

- Fresh installs do not contain `asr-test.wav`. The old “upload preset audio” button therefore fails; use the audio-file picker instead. This failure does not establish an invalid key.
- Some prompts and service-configuration field labels still say DeepSeek. The actual answer provider is selected by `assistant_provider`.
- The display/exit screen retains obsolete wording such as “paging does not extend the timer.” Consult the code and [capability map](CAPABILITY-MAP.md) for current follow-up behavior. Screen sleep, assistant exit and the follow-up window are separate timers.
- The installed app icon is labeled **AIX IO SDK Lab**; Aixleo iO Android is the project name.
