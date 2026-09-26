from pathlib import Path
import tempfile
import unittest
import lab
from test_interactions import method


class VoiceExitOwnershipChecks(unittest.TestCase):
    def test_identityless_type8_cannot_distinguish_late_old_exit_from_current_exit(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(source, x) for x in (
            'private void voiceEvent(', 'private boolean labNativeMode(', 'private boolean currentRound(',
            'private void archiveVoiceCancellation(', 'private void failStreaming(', 'private void stopStreamingCapture(',
            'private void closeStreaming(', 'private void sendNativeAnswer(', 'private void sendNativeChunk(',
            'private void startReadingWindows(', 'private void cancelDisplayCompleteWait(',
            'private void completeLabNativeDelivery(', 'private boolean handleLabNativeSend(',
            'private boolean handleLabNativeReadingExit(', 'private boolean labNativeReadingOwned(',
            'private boolean labNativeReadingActive(', 'private boolean requestLabNativeReadingExit()',
            'private boolean requestLabNativeReadingExit(String', 'private void resetLabNativeRound(',
            'private boolean nativeAnswerContinuable(', 'private long displayWaitMs(', 'private void dropWake(',
            'private boolean activateStandbyWake(', 'private void rearmStandby(',
            'private void promoteLabNativeContinuation(', 'private boolean recoverSilentLabNativeContinuation(',
            'private void armLabNativeFollowup(', 'private void stopStandby(',
            'private void startLabAssistantConversation(', 'private void clearLabAssistantConversation('))
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            generated = Path(tmp) / 'VoiceExitOwnershipCheck.java'
            generated.write_text(
                (lab.ROOT / 'tests/VoiceExitOwnershipCheck.java.in').read_text(encoding='utf-8')
                .replace('// PRODUCTION_METHODS', methods), encoding='utf-8')
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         generated, lab.ROOT / 'app/src/AssistantConversation.java', lab.ROOT / 'app/src/NativeAnswerRound.java', lab.ROOT / 'app/src/KnowledgeWaitingPreface.java',
                         lab.ROOT / 'app/src/VoiceRecordingAction.java', lab.ROOT / 'app/src/VoicePhrase.java', lab.ROOT / 'app/src/VoiceExitPhrase.java', lab.ROOT / 'app/src/VoiceAnswerArchive.java', lab.ROOT / 'app/src/CloudFailureNotice.java',
                         lab.ROOT / 'app/src/BusinessEnvelope.java', lab.ROOT / 'app/src/VoiceWakePolicy.java', lab.ROOT / 'app/src/DisplayedAnswerPolicy.java',
                         lab.ROOT / 'tests/stubs/android/os/SystemClock.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            run = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.VoiceExitOwnershipCheck'])
            self.assertIn(b'voice exit ownership checks passed', run.stdout)
            self.assertIn(b'G0 indistinguishable', run.stdout)
            self.assertIn(b'states_identical=true distinguishable=false', run.stdout)
            self.assertIn(b'G0 delivered_round_boundary=true', run.stdout)
