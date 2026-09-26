import tempfile
from pathlib import Path
from unittest.mock import patch
import unittest
import lab
import session
from test_interactions import method


class AsrOnlyChecks(unittest.TestCase):
    def test_state_machine_real_wake_endpoint_failures_and_identity(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/LabAsrTrial.java', lab.ROOT / 'tests/LabAsrTrialCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                  'dev.xr.rayneo.probe.LabAsrTrialCheck'])
            self.assertIn(b'ASR lifecycle checks passed', result.stdout)

    def test_command_rejects_daily_before_device_and_sets_bounded_wait(self):
        for kind in ('lab-asr-trial', 'lab-asr-trial-1500', 'lab-asr-segments-8s', 'lab-asr-pause-trial'):
            with self.subTest(kind=kind), patch.object(lab, 'adb_result') as adb:
                with self.assertRaises(ValueError):
                    session.send({}, 'test', kind, package=lab.PACKAGE)
                adb.assert_not_called()
            with patch('sys.argv', ['session.py', kind, '--serial', 'test', '--profile', 'sdk-lab']), \
                    patch.object(lab, 'settings', return_value={}), \
                    patch.object(session, 'send', return_value={'status': 'completed'}) as send, patch('builtins.print'):
                self.assertEqual(session.main(), 0)
                self.assertEqual(send.call_args.args[2], kind)
                self.assertEqual(send.call_args.kwargs['timeout'], 140)

    def test_production_session_payload_and_default_constructor(self):
        source = (lab.ROOT / 'app/src/StreamingAsr.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(source, sig) for sig in (
            'StreamingAsr(JSONObject config, Listener listener) {',
            'StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs) {',
            'StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs, int captureWindowMs)',
            'StreamingAsr(JSONObject config, Listener listener, int silenceDurationMs, int captureWindowMs, int continuationGraceMs)',
            'static JSONObject sessionSettings('))
        template = (lab.ROOT / 'tests/AsrSessionSettingsCheck.java.in').read_text(encoding='utf-8')
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            generated = Path(tmp) / 'AsrSessionSettingsCheck.java'
            generated.write_text(template.replace('// PRODUCTION_METHODS', methods), encoding='utf-8')
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp, generated,
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            lab.command([lab.tool(settings, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.AsrSessionSettingsCheck'])
        self.assertIn('send("session.update", "session", sessionSettings(requestedSilenceDurationMs))', source)
        sdk = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        self.assertIn('},trial.requestedSilenceDurationMs,trial.captureWindowMs,trial.continuationGraceMs)', method(sdk, 'private void startLabAsr('))

    def test_production_diagnostic_has_no_answer_display_or_persistent_config_writes(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(source, sig) for sig in (
            'private void beginAsrTrial(', 'private void startLabAsr(', 'private void applyAsrActions(',
            'private boolean handleAsrTrialVoice(', 'private void publishAsrTrial('))
        for forbidden in ('CloudClient.ask(', 'runStreamAnswer(', 'CloudConfig.save(', '.edit()',
                          'rearmStandby(', 'nativeAnswerPayload(', 'prepareGlassesVoice('):
            self.assertNotIn(forbidden, methods)
        import re
        sends = re.findall(r'sendBusiness\("([A-Z_]+)",(\d+),', methods)
        self.assertEqual(set(sends), {('VOICE_ASSISTANT', '2'), ('VOICE_ASSISTANT', '7')})
        voice = method(source, 'private void voiceEvent(')
        self.assertLess(voice.index('handleAsrTrialVoice'), voice.index('handleAnswerTrialVoice'))
        begin = method(source, 'private void beginAsrTrial(')
        for gate in ('connectionReady()', 'recorder.busy()', 'voiceRecording', 'autoLockPending()',
                     'crownPending()', 'headPending()', 'wakePending()', 'auto_standby'):
            self.assertIn(gate, begin)
        self.assertNotIn('deepseek_key', begin)


if __name__ == '__main__':
    unittest.main()
