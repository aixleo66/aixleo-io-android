import tempfile
import unittest
import lab


class DisplayedAnswerChecks(unittest.TestCase):
    def test_receipt_starts_the_windows_it_creates(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/DisplayedAnswerPolicy.java',
                         lab.ROOT / 'tests/DisplayedAnswerPolicyCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.DisplayedAnswerPolicyCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_both_call_sites_read_the_same_condition(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # The defect was two inline copies of this condition; only one was updated to accept the
        # receipt that starts the windows, so the gate rejected it before the inner code ran.
        # Neither call site may inline the deadline comparison again.
        self.assertIn('standbyEnabled && standbyReady && nativeReply && nativeAnswerContinuable()',
                      source)
        self.assertIn('boolean displayComplete=type==11&&nativeAnswerContinuable();', source)
        # The receipt must start the windows even when follow-up is disabled: folding the switch
        # into the same test discarded the receipt and left the fallback as the only way to close.
        self.assertIn('if(displayComplete&&awaitingDisplayComplete)startReadingWindows();', source)
        self.assertIn('boolean continuation=displayComplete&&labNativeFollowupEnabled;', source)
        self.assertEqual(source.count('nativeAnswerContinuable()'), 3)
        self.assertNotIn('type==11&&labNativeFollowupEnabled&&', source)
        self.assertNotIn('SystemClock.elapsedRealtime() < nativeFollowupUntil', source)
        self.assertNotIn('SystemClock.elapsedRealtime()<nativeFollowupUntil', source)

    def test_delivery_leaves_no_deadline_for_the_gate_to_compare(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # This is why the inlined deadline check could never pass: completing delivery clears the
        # deadlines and only startReadingWindows() sets them, which the receipt is meant to trigger.
        self.assertIn('awaitingDisplayComplete=true;', source)
        self.assertIn('nativeFollowupUntil=0;nativeDisplayUntil=0;', source)
        # The clear must follow the flag inside completeLabNativeDelivery. Other methods also
        # clear the deadlines, so anchor on the flag rather than the first global occurrence.
        flag = source.index('awaitingDisplayComplete=true;')
        self.assertNotEqual(-1, source.find('nativeFollowupUntil=0;nativeDisplayUntil=0;', flag))
        self.assertLess(source.find('nativeFollowupUntil=0;nativeDisplayUntil=0;', flag) - flag, 200)

    def test_continuation_listens_for_the_advertised_window(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # The candidate guard used a hardcoded 4000ms while the receipt advertised
        # followup_due_elapsed_ms from labNativeFollowupSeconds, so the microphone closed long
        # before the window the user was told about. Official listens for its full 10s.
        self.assertIn('},labNativeFollowupSeconds*1000L);', source)
        self.assertNotIn('},4000);', source)

    def test_display_wait_cannot_be_set_below_the_longest_real_display(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # An 8s fallback shipped on 2026-09-20 truncated a 35s answer at 23s. Destroying the page
        # early also stopped the glasses from ever reaching display completion, so the round saw
        # zero type 11 -- the evidence that produced the wrong "glasses never report it" reading.
        # Measured display times: 35.6s on our link, 33.4s official. Below the floor is never right.
        self.assertIn('return Math.max(45,source.optInt("assistant_display_wait_seconds",60));',
                      source)
        self.assertIn('private int labNativeDisplayWaitSeconds=60;', source)
        # Both config paths must go through the floor, not read the key directly.
        self.assertEqual(source.count('displayWaitSeconds('), 3)
        self.assertNotIn('optInt("assistant_display_wait_seconds",8)', source)

    def test_harness_display_wait_matches_production(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        production = source.split('private int labNativeDisplayWaitSeconds=')[1].split(';')[0]
        # The harness ran 60 while production shipped 8, so no offline test could have caught the
        # truncation. Keep the stubs pinned to the production default.
        for stub in ('tests/NativeAnswerIntegrationCheck.java.in',
                     'tests/VoiceExitOwnershipCheck.java.in'):
            text = (lab.ROOT / stub).read_text(encoding='utf-8')
            self.assertIn('int labNativeDisplayWaitSeconds=%s;' % production, text, stub)

    def test_silent_exit_uses_a_reason_that_rearms_standby(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # The invariant: a silent exit MUST end with standby re-armed. Skip that and voiceArmed
        # stays false, the next hardware wake is dropped, and the lens says "please connect the
        # glasses" on the following round -- observed 2026-09-20.
        #
        # 2026-09-22 tried removing the type 7 here for official parity and the device refuted it:
        # the page stopped closing (glasses' own type 8 came 23.8s late) and the wake gap stayed.
        # Reverted the same day.
        self.assertIn('if(error==null&&prefix.equals("auto_exit")){', source)
        self.assertIn('if(labNativeStandby&&!standbyReady)rearmStandby();', source)
        silent = source.split('lab_native_followup_no_speech_exit')[1][:900]
        self.assertIn('requestLabNativeReadingExit("auto_exit_requested")', silent)
        self.assertNotIn('requestLabNativeReadingExit("followup_timeout")', source)


class ConfigDefaultChecks(unittest.TestCase):
    def test_stored_defaults_agree_with_every_inline_fallback(self):
        import re
        activity = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        config = (lab.ROOT / 'app/src/CloudConfig.java').read_text(encoding='utf-8')
        # CloudConfig.load() copies defaults() into any stored configuration, so a key present
        # there always wins and the optInt/optBoolean fallback beside it is dead code. On
        # 2026-09-20 assistant_followup_seconds was changed in the fallback only and the device
        # kept running the old value. Any key named in both places must carry the same value.
        client = (lab.ROOT / 'app/src/CloudClient.java').read_text(encoding='utf-8')
        stored = dict(re.findall(r'\.put\("(\w+)",\s*(\d+|true|false)\)', config))
        # CloudClient.validateConfig holds a third copy of these defaults; an import validated
        # against a stale value silently changes the window. Independent review found
        # assistant_followup_seconds still 15 there after the other two were moved to 10.
        pattern = r'optInt\("(\w+)",\s*(\d+)\)|optBoolean\("(\w+)",\s*(true|false)\)'
        inline = re.findall(pattern, activity) + re.findall(pattern, client)
        seen = {}
        for int_key, int_val, bool_key, bool_val in inline:
            key, value = (int_key, int_val) if int_key else (bool_key, bool_val)
            seen.setdefault(key, set()).add(value)
        for key, values in sorted(seen.items()):
            self.assertEqual(1, len(values),
                             'fallback for %s disagrees with itself: %s' % (key, sorted(values)))
            if key in stored:
                self.assertEqual(stored[key], values.pop(),
                                 'CloudConfig.defaults() wins over the fallback for %s' % key)

    def test_display_wait_scales_with_the_answer_it_waits_for(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # Reading mode allows 2000 code points and each chunk carries 480 UTF-8 bytes, so a full
        # Chinese answer is about 12 chunks. A chunk was measured scrolling in 12.5s (37.37s for
        # three on 2026-09-20), so a full answer needs over two minutes. Any fixed bound truncates
        # it, and truncating destroys the page so the receipt never arrives either.
        self.assertIn('private long displayWaitMs(int chunks){', source)
        self.assertIn('Math.min(300000L,Math.max(labNativeDisplayWaitSeconds*1000L,'
                      'chunks*25000L+30000L))', source)
        self.assertIn('handler.postDelayed(displayCompleteFallback,'
                      'displayWaitMs(labNativeAnswerChunks));', source)
        self.assertIn('labNativeAnswerChunks=Math.max(1,chunks.size());', source)
        # The fallback must clear the measured display time with margin at both ends of the range.
        measured_per_chunk = 12.5
        for chunks in (1, 3, 12):
            budget = min(300.0, max(60.0, chunks * 25 + 30))
            self.assertGreater(budget, chunks * measured_per_chunk,
                               'fallback fires before a %d-chunk answer finishes' % chunks)


class StuckCommandChecks(unittest.TestCase):
    def test_device_exit_closes_an_open_continuation_candidate(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # Observed 2026-09-20: the glasses sent type 8 four seconds into the continuation window.
        # The candidate owned the pending host command; its no-speech timer is keyed on
        # voiceCommand, which rearmStandby() had already replaced, so the command never completed
        # and every later wake was refused with lab_standby_wake_busy for the rest of the session.
        exit_handler = source.split('private boolean handleLabNativeReadingExit(')[1]
        exit_handler = exit_handler[:exit_handler.index('private boolean labNativeReadingOwned(')]
        self.assertIn('if(labNativeContinuationCandidate){', exit_handler)
        self.assertIn('inflight.put("status","completed")', exit_handler)
        self.assertIn('stage("lab_native_continuation_cancelled_by_device_exit"', exit_handler)
        # The candidate must be cleared before standby is re-armed, not after.
        self.assertLess(exit_handler.index('labNativeContinuationCandidate=false'),
                        exit_handler.index('if(labNativeStandby&&!standbyReady)rearmStandby();'))

    def test_a_stale_voice_command_cannot_block_wakes_forever(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # an unfinished command blocks the session and does not self-heal. A voice
        # round whose id is no longer voiceCommand can never complete, so it must be abandoned
        # rather than used to refuse every later wake. Non-voice commands keep their own identity
        # and must still block.
        self.assertIn('if(!host.optString("kind").startsWith("voice-")'
                      '||host.optString("id").equals(voiceCommand)){', source)
        self.assertIn('host.put("status","abandoned").put("reason","round_no_longer_current");',
                      source)
        self.assertIn('stage("lab_standby_stale_command_cleared"', source)


    def test_exit_send_failure_still_rearms(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # A failed exit frame used to skip the re-arm, leaving voiceArmed false so later wakes were
        # dropped without even a lab_standby_wake_busy to explain why. The round is over locally
        # either way. One copy only: a second inline re-arm is how every defect today was born.
        self.assertIn('if(prefix.equals("auto_exit")&&labNativeStandby&&!standbyReady){', source)
        block = source.split('String prefix=state.substring')[1][:1800]
        self.assertEqual(block.count('rearmStandby();'), 1)


class DroppedWakeChecks(unittest.TestCase):
    def test_no_wake_is_discarded_without_a_record(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # A wake used to vanish with no trace, so "press the button, glasses say please connect"
        # could only be found by a person testing it -- twice on 2026-09-20, for two different
        # reasons, each costing a full round to diagnose. Every discard now names its reason, so
        # the whole class shows up in the session receipt immediately.
        body = source.split('private void voiceEvent(')[1]
        body = body[:body.index('private void ', 10)]
        self.assertIn('if (!voiceArmed) { dropWake(wire.type, standbyEnabled ? "standby_not_armed"'
                      ' : "standby_off"); return; }', body)
        self.assertIn('if(!activateStandbyWake(wire.type)){dropWake(wire.type,"standby_wake_refused");'
                      'return;}', body)
        self.assertNotIn('if (!voiceArmed) return;', source)
        self.assertIn('result.put("wakes_dropped_total"', source)
        self.assertIn('stage("voice_wake_dropped"', source)

    def test_a_wake_cancels_the_auto_restore_backoff(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # Saving a recording holds the restore for three seconds to let the transport release.
        # A user pressing the button inside that window is the strongest possible signal that they
        # want the assistant now, and used to be told to connect the glasses instead.
        self.assertIn('nextStandbyAttempt=SystemClock.elapsedRealtime()+3000;', source)
        drop = source.split('private void dropWake(')[1]
        drop = drop[:drop.index('private boolean nativeAnswerContinuable(')]
        self.assertIn('nextStandbyAttempt=0;', drop)
        self.assertIn('stage("standby_restore_expedited"', drop)
        self.assertIn('restoreStandbyIfNeeded();', drop)
        # It must not expedite while a recording still claims the transport.
        # recorder.busy() is false from phase "saved" onward, so it alone cannot say whether a
        # recording is finished with the channel; recordingTransportOwned can. Since option 甲
        # (2026-09-21) nothing tears the link down, but re-arming standby behind a preparation
        # that is still claiming the channel is still the wrong moment.
        self.assertIn('!recordingPreparing&&!recordingTransportOwned', drop)
        self.assertIn('(recorder==null||!recorder.busy())', drop)

    def test_ending_a_round_also_ends_the_display_wait(self):
        """Measured 2026-09-21, the observed session answer at +387.4s, glasses exit at +392.7s,
        and the display-complete fallback still fired at +447.4s and started the reading windows
        for that dead round. nativeDisplayUntil moved 15 seconds into the future, so for those 15
        seconds every phone notification was refused as lens_showing_answer while the lens was
        blank -- the same blocked-notification failure, produced by a timer rather than a command.
        """
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # The flag may only be cleared in the two places that mean "the wait is over": the receipt
        # arrived, or the round ended. A third clear elsewhere is how the first one got missed.
        self.assertEqual(source.count('awaitingDisplayComplete=false;'), 2,
                         'awaitingDisplayComplete is cleared somewhere new')
        windows = source.split('private void startReadingWindows(')[1]
        self.assertIn('awaitingDisplayComplete=false;', windows[:400],
                      'the receipt path stopped clearing the wait')

        cancel = source.split('private void cancelDisplayCompleteWait(')[1]
        cancel = cancel[:cancel.index('private boolean requestLabNativeReadingExit(')]
        self.assertIn('handler.removeCallbacks(displayCompleteFallback)', cancel)
        self.assertIn('awaitingDisplayComplete=false;', cancel)

        # Every way a native reading round can end must go through it.
        for ending in ('cancelDisplayCompleteWait("device_exit_received");',
                       'cancelDisplayCompleteWait(requestedState);',
                       'cancelDisplayCompleteWait("round_reset");'):
            self.assertIn(ending, source, 'a round can end while the display wait survives it')

        # resetLabNativeRound exists to cancel the round's timers. It cancelled two of the three,
        # and the one it missed is the one that could restart the reading windows.
        reset = source.split('private void resetLabNativeRound(')[1]
        reset = reset[:reset.index('\n    }')]
        for timer in ('labNativeAutoExit', 'labNativeExitConfirmation', 'cancelDisplayCompleteWait'):
            self.assertIn(timer, reset, 'resetLabNativeRound leaves %s running' % timer)

        # The fallback still refuses to run for a round that has been replaced. That guard was in
        # place all along and was not enough: the round had ended without being replaced.
        fallback = source.split('displayCompleteFallback=()->{')[1]
        self.assertIn('labNativeRound==round', fallback[:300])
