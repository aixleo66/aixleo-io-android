import os
from pathlib import Path
import tempfile
import unittest
import lab


def method(source, signature):
    """Copy production method bodies verbatim; the harness supplies only platform edges."""
    start = source.index(signature)
    opening = source.index('{', start)
    depth, quote, escape = 0, None, False
    for end in range(opening, len(source)):
        char = source[end]
        if quote:
            if escape:
                escape = False
            elif char == '\\':
                escape = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise AssertionError(signature)


class InteractionChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.settings = lab.settings()
        cls.temp = tempfile.TemporaryDirectory(prefix='rayneo-interaction-')
        cls.directory = Path(cls.temp.name)
        cls.addClassCleanup(cls.temp.cleanup)
        source = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(source, signature) for signature in (
            'private String renderAnswer(', 'private void clearAnswer(', 'private void cancelPhoneJob(',
            'private void sendToGlasses(', 'private void sendSessionCommand(String kind, JSONObject notification)',
            'private void sendSessionCommand(String kind, JSONObject notification, String expectedSession, JSONObject pipeline)'))
        harness = (lab.ROOT / 'tests/ActivityInteractionCheck.java.in').read_text(encoding='utf-8').replace('// PRODUCTION_METHODS', methods)
        generated = cls.directory / 'ActivityInteractionCheck.java'
        generated.write_text(harness, encoding='utf-8')
        cls.dependencies = os.pathsep.join(map(str, [cls.settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        names = ('CloudClient.java', 'CloudConfig.java', 'AudioInput.java', 'AnswerPolicy.java', 'KnowledgeClient.java',
                 'KnowledgeRunState.java', 'CloudPipeline.java', 'AnswerResult.java', 'CommandWait.java',
                 'GlassesRecorder.java', 'RecordingFile.java', 'BusinessEnvelope.java')
        lab.command([lab.tool(cls.settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-classpath', cls.dependencies, '-d', cls.directory,
                     *[lab.ROOT / 'app/src' / n for n in names], *list((lab.ROOT / 'tests/stubs').rglob('*.java')),
                     generated, lab.ROOT / 'tests/CloudPipelineCheck.java', lab.ROOT / 'tests/RecorderLifecycleCheck.java'])

    def run_check(self, name, scenario=''):
        with tempfile.TemporaryDirectory(prefix='rayneo-fixture-') as files:
            result = lab.command([lab.tool(self.settings, 'java'), '-cp', str(self.directory) + os.pathsep + self.dependencies,
                                  'dev.xr.rayneo.probe.' + name, scenario, files])
            self.assertIn(b'passed', result.stdout)

    def test_cancel_during_input_and_asr_prevents_new_requests(self):
        self.run_check('CloudPipelineCheck')

    def test_disconnect_interrupts_voice_record_preparation_and_pending_request(self):
        self.run_check('ActivityInteractionCheck', 'interrupt')

    def test_rendered_answer_b_is_the_manually_submitted_answer(self):
        self.run_check('ActivityInteractionCheck', 'answer')

    def test_phone_cancel_stops_capture_and_invalidates_waits(self):
        self.run_check('ActivityInteractionCheck', 'cancel')

    def test_device_stop_without_completion_times_out_preserving_original(self):
        self.run_check('RecorderLifecycleCheck', 'timeout')

    def test_repeated_stop_does_not_extend_completion_deadline(self):
        self.run_check('RecorderLifecycleCheck', 'repeat')

    def test_completion_prevents_stop_regression_and_missing_report_timeout(self):
        self.run_check('RecorderLifecycleCheck', 'completion')

    def test_old_recording_timeout_cannot_fail_a_different_id(self):
        self.run_check('RecorderLifecycleCheck', 'stale')

    def test_stop_ack_failure_still_has_a_completion_deadline(self):
        self.run_check('RecorderLifecycleCheck', 'ack-failure')

    def test_activity_wires_shared_cancel_and_unified_answer(self):
        source = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        file_job = method(source, 'private void startJob(')
        voice_job = method(source, 'private void startPhoneVoice(')
        self.assertIn('CloudPipeline.transcribe(cancel,', file_job)
        self.assertIn('CloudPipeline.voice(cancel,', voice_job)
        self.assertIn('jobCancellation=cancel', voice_job)
        self.assertIn('CloudClient.transcribe(settings, audio, mime,c)', file_job)
        self.assertIn('CloudClient.transcribe(settings,wav,"audio/wav",c)', voice_job)
        self.assertIn('CloudClient.ask(settings, transcript,c)', voice_job)
        self.assertIn('renderAnswer(completed)', voice_job)
        self.assertNotIn('lastLensText', source)
        self.assertNotIn('lastText', source)
