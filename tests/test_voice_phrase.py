import tempfile
import unittest
import lab


class VoicePhraseChecks(unittest.TestCase):
    def test_explicit_commands_reach_their_actions(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/VoicePhrase.java',
                         lab.ROOT / 'app/src/VoiceExitPhrase.java',
                         lab.ROOT / 'app/src/VoiceRecordingAction.java',
                         lab.ROOT / 'app/src/VoiceTodoAction.java',
                         lab.ROOT / 'tests/VoicePhraseCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.VoicePhraseCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_every_matcher_shares_one_normalizer(self):
        # All eight defects fixed on 2026-09-20 came from one piece of logic living in two places
        # and only one copy being updated. These three matchers need the same filler stripping and
        # the same question guard, so they must read them from VoicePhrase rather than inline them.
        for name in ('VoiceExitPhrase.java', 'VoiceRecordingAction.java', 'VoiceTodoAction.java'):
            source = (lab.ROOT / 'app/src' / name).read_text(encoding='utf-8')
            self.assertIn('VoicePhrase.normalize(', source, name)
            self.assertNotIn('replaceFirst("^(?:请帮我|帮我|请)', source, name)

    def test_voice_exit_reuses_the_reason_that_rearms_standby(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # A private reason string would skip the re-arm branch in handleLabNativeSend and leave the
        # session unable to accept the next wake, which is the defect fixed earlier the same day.
        handler = source.split('private void handoffVoiceExit(')[1]
        handler = handler[:handler.index('private void handoffVoiceRecording(')]
        self.assertIn('requestLabNativeReadingExit("auto_exit_requested")', handler)
        self.assertNotIn('requestLabNativeReadingExit("voice_exit', handler)
        # The pending host command must be closed, or it blocks every later wake.
        self.assertIn('host.put("status","completed").put("reason","voice_exit_requested");', handler)
        # An exit request must never reach the model.
        dispatch = source.split('result.getJSONObject("voice_test").put("phase", "answering");')[1][:900]
        self.assertLess(dispatch.index('VoiceExitPhrase.matches'),
                        dispatch.index('VoiceRecordingAction.matches'))
