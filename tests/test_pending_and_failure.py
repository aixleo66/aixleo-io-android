import tempfile
import unittest
import lab


class PendingCommandChecks(unittest.TestCase):
    """A pending command silenced every phone notification, with no expiry and no trace."""

    def test_deadline_table_and_expiry(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-d', directory,
                         lab.ROOT / 'app/src/CommandDeadline.java',
                         lab.ROOT / 'tests/CommandDeadlineCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.CommandDeadlineCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_the_host_and_the_device_read_one_table(self):
        # The host's wait and the device's expiry must be the same numbers. They were one inline
        # ternary in CloudActivity; a second copy is how this project's defects are usually born.
        host = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        self.assertIn('CommandDeadline.budgetMs(kind)', host)
        self.assertNotIn('kind.equals("record-stop")?180000', host,
                         'the deadline table grew a second copy in CloudActivity')

    def test_every_pending_command_records_when_it_started(self):
        # Without a start time the gate cannot tell a working command from an abandoned one, and
        # CommandDeadline.expired deliberately keeps gating when the age is unknown.
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # Scan statements, not lines: one of these creations is wrapped across two lines, and a
        # line-based check silently skipped it.
        creations, start = [], 0
        while True:
            i = source.find('result.put("last_command"', start)
            if i < 0:
                break
            statement = ' '.join(source[i:source.index(';', i) + 1].split())
            start = i + 1
            if '"pending"' in statement:
                creations.append(statement)
        self.assertGreaterEqual(len(creations), 6, 'last_command creation sites moved')
        for statement in creations:
            self.assertIn('started_ms', statement,
                          'a pending command with no start time can gate forever: ' + statement)

    def test_a_refused_notification_leaves_a_trace(self):
        # "Notifications stopped arriving" was undiagnosable from the session: the gate returned
        # false from a single compound condition and recorded nothing at all.
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        gate = source[source.index('private boolean forwardPhoneNotification('):]
        gate = gate[:gate.index('private boolean commandStillOwnsTheLink(')]
        self.assertNotIn('return false;', gate,
                         'a refusal path still drops the notification without saying why')
        for reason in ('capture_active', 'command_pending', 'lens_showing_answer',
                       'standby_not_ready', 'connection_not_ready'):
            self.assertIn('"%s"' % reason, gate, 'lost the %s reason' % reason)

    def test_the_gate_expires_instead_of_testing_pending_directly(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        owns = source[source.index('private boolean commandStillOwnsTheLink('):]
        owns = owns[:owns.index('private boolean dropNotification(')]
        self.assertIn('CommandDeadline.expired(', owns)
        self.assertIn('command_past_deadline', owns, 'an expiry must be observable when it happens')


class CloudFailureChecks(unittest.TestCase):
    """A cloud failure ended the round silently, indistinguishable from a normal exit."""

    def test_notice_text_and_retry_policy(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-d', directory,
                         lab.ROOT / 'app/src/CloudFailureNotice.java',
                         lab.ROOT / 'app/src/CloudRetry.java',
                         lab.ROOT / 'tests/CloudFailureNoticeCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.CloudFailureNoticeCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_the_notice_is_not_limited_to_one_provider(self):
        # The working branch existed all along, spelled '"knowledge".equals(selectedProvider)'.
        # DeepSeek fell past it into failStreaming, which sends type 7 and no text.
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        self.assertNotIn('"knowledge".equals(selectedProvider)&&labNativeRound!=null', source,
                         'the failure notice is gated to one provider again')
        self.assertIn('CloudFailureNotice.lensText(selectedProvider', source)

    def test_both_answer_call_sites_go_through_the_retry(self):
        # A retry on one path only would mean the batch and streaming rounds behave differently
        # under the same network fault.
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        body = source[source.index('private JSONObject askWithOfficialRetry('):]
        body = body[:body.index('private void sendNativeAnswer(')]
        self.assertIn('CloudRetry.again(', body)
        self.assertIn('cancel.check();', body, 'a cancelled round must not revive after the delay')
        callers = source.count('askWithOfficialRetry(pipeline')
        self.assertEqual(callers, 2, 'expected the batch and streaming paths to share the retry')
        self.assertNotIn('JSONObject answer = CloudClient.ask(config', source,
                         'an answer request bypasses the retry')


if __name__ == '__main__':
    unittest.main()
