import subprocess
import tempfile
import unittest
from pathlib import Path
import lab
from display_observer import present


class BusinessChecks(unittest.TestCase):
    def test_wire_codec_with_actual_java_implementation(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory(prefix='rayneo-wire-') as tmp:
            subprocess.run([str(lab.tool(settings, 'javac')), '-encoding', 'UTF-8', '-d', tmp,
                            str(lab.ROOT / 'app/src/BusinessEnvelope.java'),
                            str(lab.ROOT / 'tests/BusinessEnvelopeCheck.java')], check=True, capture_output=True)
            proc = subprocess.run([str(lab.tool(settings, 'java')), '-cp', tmp, 'BusinessEnvelopeCheck'],
                                  check=True, capture_output=True)
            self.assertIn(b'checks passed', proc.stdout)

    def test_observer_does_not_call_old_session_live(self):
        result = {'pid': 123, 'status': 'running', 'auth_success_callback': True}
        self.assertTrue(present(result, '123')['live'])
        self.assertFalse(present(result, '124')['live'])
        self.assertFalse(present(result, '123', available=False)['live'])
        self.assertFalse(present({**result, 'status': 'sdk_text_sent'}, '123')['live'])
        self.assertNotIn('private_data', present({**result, 'private_data': 'hidden'}, '123')['result'])

    def test_wearer_confirmation_is_scoped_to_session_and_notification(self):
        result = {'session_id': 'session-a', 'submitted_text': {'uid': '123'}}
        confirmed = {'session_id': 'session-a', 'notification_uid': '123', 'source': 'user', 'status': 'confirmed_complete'}
        self.assertIn('visual_confirmation', present(result, '', confirmation=confirmed)['result'])
        self.assertNotIn('visual_confirmation', present(result, '', confirmation={**confirmed, 'session_id': 'old'})['result'])
        self.assertNotIn('visual_confirmation', present(result, '', confirmation={**confirmed, 'notification_uid': '456'})['result'])
