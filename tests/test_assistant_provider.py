import unittest
import lab


class AssistantProviderChecks(unittest.TestCase):
    """The assistant provider must stay a setting: DeepSeek returned HTTP 503 for a stretch on
    2026-09-20 while its status page reported degraded performance, and the Aliyun account already
    used for ASR also serves an OpenAI-compatible chat endpoint."""

    def setUp(self):
        self.client = (lab.ROOT / 'app/src/CloudClient.java').read_text(encoding='utf-8')
        self.config = (lab.ROOT / 'app/src/CloudConfig.java').read_text(encoding='utf-8')

    def test_provider_is_read_from_settings_not_hardcoded(self):
        self.assertIn('String provider=config.optString("assistant_provider","deepseek")', self.client)
        self.assertNotIn('call(config, "deepseek", new JSONObject().put("messages"', self.client)

    def test_dashscope_is_an_accepted_assistant_provider(self):
        self.assertIn('"deepseek","dashscope","knowledge"', self.client)

    def test_chat_does_not_reuse_the_asr_model_key(self):
        # dashscope_model is the ASR model. Reusing it for chat would send qwen3-asr-flash to a
        # chat endpoint, which fails in a way that looks like a provider outage.
        self.assertIn('dashscope_text_model', self.config)
        self.assertIn('"dashscope_text_model"', self.client)

    def test_deepseek_only_parameters_are_not_sent_to_other_providers(self):
        # thinking:disabled is DeepSeek-specific; Aliyun rejects unknown parameters.
        self.assertIn('if(provider.equals("deepseek"))body.put("thinking"', self.client)

    def test_aliyun_hosts_remain_whitelisted(self):
        for host in ('dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', '.maas.aliyuncs.com'):
            self.assertIn(host, self.client)
