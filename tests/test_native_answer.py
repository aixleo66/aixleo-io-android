from pathlib import Path
import tempfile
import unittest
import lab
from test_interactions import method


class NativeAnswerChecks(unittest.TestCase):
    def test_production_payload_chunks_ack_completion_and_ownership(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(source, x) for x in (
            'private boolean labNativeMode(', 'private boolean currentRound(',
            'private void archiveVoiceCancellation(', 'private void failStreaming(', 'private void stopStreamingCapture(',
            'private void sendNativeAnswer(', 'private void sendNativeChunk(',
            'private void startReadingWindows(', 'private void cancelDisplayCompleteWait(',
            'private void completeLabNativeDelivery(', 'private boolean handleLabNativeSend(',
            'private boolean handleLabNativeReadingExit(', 'private boolean labNativeReadingOwned(',
            'private boolean labNativeReadingActive(', 'private boolean requestLabNativeReadingExit()',
            'private boolean requestLabNativeReadingExit(String', 'private void resetLabNativeRound(',
            'private boolean nativeAnswerContinuable(', 'private long displayWaitMs(',
            'private boolean activateStandbyWake(', 'private void rearmStandby(',
            'private void promoteLabNativeContinuation(', 'private boolean recoverSilentLabNativeContinuation(',
            'private void armLabNativeFollowup(', 'private void stopStandby(',
            'private void startKnowledgeWaitingPreface(',
            'private void startLabAssistantConversation(', 'private void clearLabAssistantConversation('))
        wake = method(source, 'private void voiceEvent(')
        capture = method(source, 'private void startVoiceCapture(')
        self.assertLess(wake.index('activateStandbyWake(wire.type)'), wake.index('new NativeAnswerRound('))
        self.assertIn('labNativeAutoExitSeconds=config.optInt("assistant_auto_exit_seconds",15)', capture)
        self.assertIn('labNativeFollowupSeconds=config.optInt("assistant_followup_seconds",10)', capture)
        # Official behaviour (user decision 2026-09-20): silence at the end of the single
        # listening window closes the page; no second reading window is started.
        self.assertIn('long followupWindow=labNativeFollowupSeconds*1000L', source)
        self.assertIn('.put("reading_window_restarted",false)', source)
        # 2026-09-22: dropping this type 7 was tried and refuted on device the same day. Without
        # it the glasses stayed on the answer page and only sent their own type 8 exit 23.8s later,
        # and the wake gap was unchanged. Keep the frame. See the note in the production method.
        self.assertIn('.put("exit_sent",true)', source)
        self.assertIn('requestLabNativeReadingExit("auto_exit_requested");return true;', source)
        self.assertNotIn('.put("reading_window_restarted",true)', source)
        self.assertIn('.put("followup_window_ms",followupWindow)', source)
        handoff = method(source, 'private void handoffVoiceRecording(')
        handoff_end = handoff[handoff.index('        endVoiceRound(command,"recording_handoff");'):handoff.index('resetLabNativeRound();nativeReply=false;')]
        s = lab.settings()
        cloud = (lab.ROOT / 'app/src/CloudClient.java').read_text(encoding='utf-8')
        cloud_methods = '\n'.join(method(cloud, x) for x in (
            'static boolean usesKnowledge(',
            'static JSONObject ask(JSONObject config, String prompt, Cancellation cancel)',
            'static JSONObject askReadingTrial(JSONObject config,String prompt,Cancellation cancel)',
            'static JSONObject askReadingTrial(JSONObject config,String prompt,JSONArray history,Cancellation cancel)',
            'private static JSONObject ask(JSONObject config,String prompt,Cancellation cancel,boolean readingTrial)',
            'private static JSONObject ask(JSONObject config,String prompt,JSONArray history,Cancellation cancel,boolean readingTrial)'))
        with tempfile.TemporaryDirectory() as tmp:
            generated = Path(tmp) / 'NativeAnswerIntegrationCheck.java'
            generated.write_text((lab.ROOT / 'tests/NativeAnswerIntegrationCheck.java.in').read_text(encoding='utf-8').replace('// PRODUCTION_METHODS', methods).replace('// CLOUD_METHODS',cloud_methods).replace('// PRODUCTION_HANDOFF_END', handoff_end), encoding='utf-8')
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         generated, lab.ROOT / 'app/src/AssistantConversation.java', lab.ROOT / 'app/src/NativeAnswerRound.java', lab.ROOT / 'app/src/KnowledgeWaitingPreface.java', lab.ROOT / 'app/src/VoiceAnswerArchive.java', lab.ROOT / 'app/src/CloudFailureNotice.java', lab.ROOT / 'app/src/ReadingExitJournal.java', lab.ROOT / 'app/src/DisplayedAnswerPolicy.java',
                         lab.ROOT / 'tests/KnowledgeWaitingPrefaceCheck.java', lab.ROOT / 'tests/ReadingExitJournalCheck.java',
                         lab.ROOT / 'tests/stubs/android/os/SystemClock.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.NativeAnswerIntegrationCheck'])
            self.assertIn(b'production dispatch checks passed', result.stdout)
            preface = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.KnowledgeWaitingPrefaceCheck'])
            self.assertIn(b'knowledge waiting preface checks passed', preface.stdout)
            exits = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.ReadingExitJournalCheck'])
            self.assertIn(b'reading exit journal checks passed', exits.stdout)
