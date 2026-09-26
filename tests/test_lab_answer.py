import tempfile
from pathlib import Path
from test_interactions import method
import unittest
from unittest.mock import patch
import lab
import session

class AnswerChecks(unittest.TestCase):
    def test_lifecycle_failure_late_callbacks_timeout_and_cancel(self):
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,lab.ROOT/'app/src/LabAnswerTrial.java',lab.ROOT/'tests/LabAnswerTrialCheck.java'])
            self.assertIn(b'checks passed',lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.LabAnswerTrialCheck']).stdout)
    def test_daily_rejected_before_device(self):
        with patch.object(lab,'adb_result') as adb:
            with self.assertRaises(ValueError):session.send({},'unused','lab-answer-trial',package=lab.PACKAGE)
            adb.assert_not_called()
    def test_cli_exact_route_and_timeout(self):
        with patch('sys.argv',['session.py','lab-answer-trial','--serial','test','--profile','sdk-lab']), patch.object(lab,'settings',return_value={}), patch.object(session,'send',return_value={'status':'completed'}) as send, patch('builtins.print'):
            self.assertEqual(session.main(),0)
            self.assertEqual(send.call_args.args[2],'lab-answer-trial')
            self.assertEqual(send.call_args.kwargs['timeout'],90)
            self.assertEqual(send.call_args.kwargs['package'],lab.LAB_PACKAGE)

    def test_production_wake_dispatch_finish_and_notification_resume(self):
        settings=lab.settings()
        source=(lab.ROOT/'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        methods='\n'.join(method(source,signature) for signature in (
            'private void publishAnswerTrial(', 'private void advanceAnswerTrial(',
            'private JSONObject nativeAnswerPayload(', 'private boolean handleAnswerTrialVoice(',
            'private boolean forwardPhoneNotification(', 'private boolean commandStillOwnsTheLink(',
            'private boolean dropNotification('))
        with tempfile.TemporaryDirectory() as tmp:
            generated=Path(tmp)/'AnswerWakeIntegrationCheck.java'
            generated.write_text((lab.ROOT/'tests/AnswerWakeIntegrationCheck.java.in').read_text(encoding='utf-8').replace('// PRODUCTION_METHODS',methods),encoding='utf-8')
            lab.command([lab.tool(settings,'javac'),'-encoding','UTF-8','-d',tmp,generated,
                lab.ROOT/'app/src/LabAnswerTrial.java',lab.ROOT/'app/src/LabAsrTrial.java',lab.ROOT/'app/src/BusinessEnvelope.java',lab.ROOT/'app/src/CommandDeadline.java',
                lab.ROOT/'tests/stubs/android/os/SystemClock.java',*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            result=lab.command([lab.tool(settings,'java'),'-cp',tmp,'dev.xr.rayneo.probe.AnswerWakeIntegrationCheck'])
            self.assertIn(b'notification resumption passed',result.stdout)
