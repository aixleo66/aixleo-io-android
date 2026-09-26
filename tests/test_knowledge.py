import tempfile
import unittest
import lab
import os

class KnowledgeConfigChecks(unittest.TestCase):
    def test_legacy_global_choice_does_not_require_knowledge_for_default(self):
        settings = lab.settings()
        dependencies = os.pathsep.join(map(str, [settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-classpath', dependencies, '-d', directory,
                         *[lab.ROOT / 'app/src' / name for name in ('AssistantConversation.java', 'CloudClient.java', 'CloudConfig.java', 'ConfigPersistence.java', 'AudioInput.java', 'AnswerPolicy.java', 'KnowledgeClient.java', 'KnowledgeRunState.java')],
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java')),
                         lab.ROOT / 'tests/KnowledgeConfigCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory + os.pathsep + dependencies,
                                  'dev.xr.rayneo.probe.KnowledgeConfigCheck'])
            self.assertIn(b'knowledge config checks passed', result.stdout)

class KnowledgeChecks(unittest.TestCase):
    def test_wss_connect_cancellation_releases_next_request(self):
        settings = lab.settings()
        dependencies = os.pathsep.join(map(str, [settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-classpath', dependencies, '-d', directory,
                         *[lab.ROOT / 'app/src' / name for name in ('AssistantConversation.java', 'CloudClient.java', 'CloudConfig.java', 'ConfigPersistence.java', 'AudioInput.java', 'AnswerPolicy.java', 'KnowledgeClient.java', 'KnowledgeRunState.java')],
                         lab.ROOT / 'tests/stubs/android/os/SystemClock.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java')),
                         *list((lab.ROOT / 'tests/knowledge_cancel_stubs').rglob('*.java')),
                         lab.ROOT / 'tests/KnowledgeCancellationCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.KnowledgeCancellationCheck'])
            self.assertIn(b'permits immediate next request passed', result.stdout)

    def test_run_correlation_completion_and_replay(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/KnowledgeRunState.java', lab.ROOT / 'tests/KnowledgeRunStateCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.KnowledgeRunStateCheck'])
            self.assertIn(b'knowledge run checks passed', result.stdout)
