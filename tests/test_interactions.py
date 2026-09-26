import os
from pathlib import Path
import tempfile
import unittest
import lab


def method(source, signature):
    """Copy production method bodies verbatim; the harness supplies only platform edges.

    Comments are skipped, not scanned. An apostrophe in ordinary prose ("the user's account")
    used to open a character literal that never closed, so the brace counter ran off the end of
    the file and the method simply could not be found. That cost a debugging round on 2026-09-20
    and again on 2026-09-21; making the scanner understand comments ends it rather than asking
    every future comment to avoid the word "app's".
    """
    start = source.index(signature)
    opening = source.index('{', start)
    depth, quote, escape, comment = 0, None, False, None
    end = opening
    while end < len(source):
        char = source[end]
        pair = source[end:end + 2]
        if comment == '//':
            if char == '\n':
                comment = None
        elif comment == '/*':
            if pair == '*/':
                comment, end = None, end + 1
        elif quote:
            if escape:
                escape = False
            elif char == '\\':
                escape = True
            elif char == quote:
                quote = None
        elif pair in ('//', '/*'):
            comment, end = pair, end + 1
        elif char in ('"', "'"):
            quote = char
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
        end += 1
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
            'private String renderAnswer(', 'private void refreshGlassesAnswer(', 'private void clearAnswer(', 'private void cancelPhoneJob(',
            'private void sendToGlasses(', 'private void sendSessionCommand(String kind, JSONObject notification)',
            'private void sendSessionCommand(String kind, JSONObject notification, String expectedSession, JSONObject pipeline)'))
        harness = (lab.ROOT / 'tests/ActivityInteractionCheck.java.in').read_text(encoding='utf-8').replace('// PRODUCTION_METHODS', methods)
        generated = cls.directory / 'ActivityInteractionCheck.java'
        generated.write_text(harness, encoding='utf-8')
        sdk = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        dispatch = cls.directory / 'DeviceRecordingDispatchCheck.java'
        dispatch.write_text((lab.ROOT / 'tests/DeviceRecordingDispatchCheck.java.in').read_text(encoding='utf-8').replace(
            '// PRODUCTION_METHODS', method(sdk, 'private void receiveDeviceRecordingStart(')), encoding='utf-8')
        transport = cls.directory / 'RecordingTransportCheck.java'
        transport_methods = '\n'.join(method(sdk, signature) for signature in (
            'private void recordingTransportTrace(', 'private void prepareRecordingTask(String deviceId)',
            'private void startRecordingTask()', 'private void failRecordingPreparation()',
            'private void cancelRecordingPreparation(', 'private void endRecordingTransportUse(',
            'private boolean submitTransportRelease('))
        transport.write_text((lab.ROOT / 'tests/RecordingTransportCheck.java.in').read_text(encoding='utf-8').replace(
            '// PRODUCTION_METHODS', transport_methods.replace('SdkProbeActivity.this', 'RecordingTransportCheck.this')), encoding='utf-8')
        cls.dependencies = os.pathsep.join(map(str, [cls.settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        names = ('CommandDeadline.java', 'AssistantConversation.java', 'CloudClient.java', 'CloudConfig.java', 'ConfigPersistence.java', 'AudioInput.java', 'AnswerPolicy.java', 'KnowledgeClient.java',
                 'KnowledgeRunState.java', 'CloudPipeline.java', 'AnswerResult.java', 'CommandWait.java',
                 'GlassesRecorder.java', 'RecordingTimeline.java', 'RecordingProgress.java', 'RecordingFile.java', 'RecordingMarkStore.java', 'OggOpusWriter.java',
                 'LabAsrTrial.java', 'LabAnswerTrial.java', 'LabDisplayTrial.java', 'BusinessEnvelope.java', 'ShellNavigation.java')
        lab.command([lab.tool(cls.settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-classpath', cls.dependencies, '-d', cls.directory,
                     *[lab.ROOT / 'app/src' / n for n in names], *list((lab.ROOT / 'tests/stubs').rglob('*.java')),
                     generated, dispatch, transport, lab.ROOT / 'tests/CloudPipelineCheck.java', lab.ROOT / 'tests/RecorderLifecycleCheck.java', lab.ROOT / 'tests/RecorderQueueCheck.java', lab.ROOT / 'tests/RecorderStorageFaultCheck.java', lab.ROOT / 'tests/DeviceRecordingCheck.java', lab.ROOT / 'tests/ShellNavigationCheck.java'])

    def test_recording_ends_its_transport_claim_without_handing_spp_back(self):
        self.run_check('RecordingTransportCheck')

    def test_receipt_write_failure_preserves_received_original(self):
        self.run_check('RecorderStorageFaultCheck', 'receipt-tmp-directory')

    def test_raw_write_failure_preserves_previous_bytes(self):
        self.run_check('RecorderStorageFaultCheck', 'closed-receiver')

    def test_queue_capacity_overflow_drain_and_storage_close(self):
        self.run_check('RecorderQueueCheck')

    def test_device_recording_admission_requires_idle_owned_connection(self):
        self.run_check('DeviceRecordingDispatchCheck')

    def test_device_recording_acceptance_duplicates_stop_and_file_preservation(self):
        self.run_check('DeviceRecordingCheck')

    def test_shell_navigation_preserves_origin_and_clears_history_between_tabs(self):
        self.run_check('ShellNavigationCheck')

    def run_check(self, name, scenario=''):
        with tempfile.TemporaryDirectory(prefix='rayneo-fixture-') as files:
            result = lab.command([lab.tool(self.settings, 'java'), '-cp', str(self.directory) + os.pathsep + self.dependencies,
                                  'dev.xr.rayneo.probe.' + name, scenario, files])
            self.assertIn(b'passed', result.stdout)
            if name in ('RecorderQueueCheck', 'RecordingTransportCheck', 'RecorderStorageFaultCheck'):
                print(result.stdout.decode('utf-8'))

    def test_cancel_during_input_and_asr_prevents_new_requests(self):
        self.run_check('CloudPipelineCheck')

    def test_disconnect_interrupts_voice_record_preparation_and_pending_request(self):
        self.run_check('ActivityInteractionCheck', 'interrupt')

    def test_rendered_answer_b_is_the_manually_submitted_answer(self):
        self.run_check('ActivityInteractionCheck', 'answer')

    def test_background_failure_visible_without_resending_old_answer(self):
        self.run_check('ActivityInteractionCheck', 'background-result')

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
