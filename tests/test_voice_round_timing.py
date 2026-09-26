import json
import os
from pathlib import Path
import tempfile
import unittest
import lab


class VoiceRoundTimingChecks(unittest.TestCase):
    def test_round_identity_terminal_semantics_and_persisted_receipts(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory(prefix='voice-round-timing-') as directory:
            root = Path(directory)
            source = (lab.ROOT / 'app/src/VoiceRoundJournal.java').read_text(encoding='utf-8')
            # Host filesystem seam, as in RecordingTimingChecks. No Android fsync claim.
            original = 'tmp.renameTo(new File(root,name))'
            self.assertEqual(source.count(original), 1)
            source = source.replace(original, '(java.nio.file.Files.move(tmp.toPath(),new File(root,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)!=null)')
            copied = root / 'VoiceRoundJournal.java'
            copied.write_text(source, encoding='utf-8')
            dependencies = os.pathsep.join(map(str, [settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-classpath', dependencies, '-d', root,
                         copied, lab.ROOT / 'tests/VoiceRoundJournalCheck.java',
                         lab.ROOT / 'tests/stubs/org/json/JSONObject.java', lab.ROOT / 'tests/stubs/org/json/JSONArray.java'])
            receipts = root / 'receipts'
            run = lab.command([lab.tool(settings, 'java'), '-cp', str(root) + os.pathsep + dependencies,
                               'dev.xr.rayneo.probe.VoiceRoundJournalCheck', receipts])
            self.assertIn(b'passed', run.stdout)
            data = {p.stem: json.loads(p.read_text(encoding='utf-8')) for p in receipts.glob('*.json')}
            self.assertEqual(set(data), {'session-A--round-A', 'session-A--round-B', 'session-A--round-C'})
            a, b, c = (data['session-A--round-' + name] for name in 'ABC')
            self.assertEqual(a['status'], 'cancelled_by_device')
            self.assertNotIn('answer_end_send_completed', a['offset_ms'])
            self.assertIn('asr_no_input', b['offset_ms'])
            self.assertNotIn('first_audio_received', b['offset_ms'])
            self.assertEqual(c['status'], 'answer_sent')
            self.assertEqual(c['lens_observation'], 'not_collected')

    def test_real_entry_points_preserve_observation_boundaries(self):
        from test_interactions import method
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        voice = method(source, 'private void voiceEvent(')
        idle_exit = voice[voice.index('if (wire.type == 8 && standbyReady'):voice.index('if (wire.type == 8 && nativeReply')]
        self.assertNotIn('voiceTiming(', idle_exit)
        self.assertNotIn('endVoiceRound(', idle_exit)
        self.assertIn('"device_exit_cancels_active_round"', voice)
        rearm = method(source, 'private void rearmStandby(')
        self.assertLess(rearm.index('listenerRearmed('), rearm.index('voiceCommand = UUID'))
        self.assertNotIn('device_exit', rearm)
        failure = method(source, 'private void runGlassesCloud(')
        self.assertIn('endVoiceRound(command,"failed")', failure)
        finish = method(source, 'private void finishVoice(')
        self.assertIn('boolean diagnosticOk=ok&&!cloudVoice;', finish)
        self.assertIn('endVoiceRound(ending,diagnosticOk?', finish)
        answer = method(source, 'private void runStreamAnswer(')
        self.assertLess(answer.index('roundTiming.mark(command,"answer_request_started"'), answer.index('CloudClient.ask('))
        self.assertLess(answer.index('roundTiming.mark(command,"answer_request_started"'), answer.index('handler.post('))
        stream = (lab.ROOT / 'app/src/StreamingAsr.java').read_text(encoding='utf-8')
        self.assertIn('endpointReason="vad_stopped"', stream)
        self.assertIn('endpointReason="final_text_fallback"', stream)
        self.assertIn('default void timing(', stream)
