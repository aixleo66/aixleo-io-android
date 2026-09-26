from pathlib import Path
import tempfile
import unittest
import lab

class AssistantConversationChecks(unittest.TestCase):
    def test_bounded_memory_context_and_request_order(self):
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',root,
                lab.ROOT/'app/src/AssistantConversation.java',lab.ROOT/'tests/AssistantConversationCheck.java',
                *list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            result=lab.command([lab.tool(s,'java'),'-cp',root,'dev.xr.rayneo.probe.AssistantConversationCheck'])
            self.assertIn(b'checks passed',result.stdout)

    def test_activity_commits_only_after_delivery_and_clears_on_exit(self):
        source=(lab.ROOT/'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        delivery=source[source.index('private void completeLabNativeDelivery'):source.index('private boolean handleLabNativeReadingExit')]
        self.assertIn('labAssistantConversation.commit(query,answer)',delivery)
        self.assertIn('if(success&&!failedAnswer&&labAssistantConversationActive)',delivery)
        self.assertIn('clearLabAssistantConversation("device_exit_received")',source)
        self.assertIn('clearLabAssistantConversation("device_exit_cancelled_round")',source)
        self.assertIn('clearLabAssistantConversation(requestedState)',source)
        self.assertIn('clearLabAssistantConversation("standby_stopped")',source)
        self.assertIn('clearLabAssistantConversation("round_failed")',source)

if __name__=='__main__': unittest.main()
