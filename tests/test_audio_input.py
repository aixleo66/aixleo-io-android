import tempfile
import unittest
import wave
from pathlib import Path
import lab


class AudioInputChecks(unittest.TestCase):
    def test_stream_resampling_preserves_speech_and_rejects_aliases(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/PcmDownsample.java', lab.ROOT / 'tests/PcmDownsampleCheck.java'])
            lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.PcmDownsampleCheck'])

    def test_hardware_wakeup_and_next_turn_sequences(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/VoiceWakePolicy.java', lab.ROOT / 'tests/VoiceWakePolicyCheck.java'])
            lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.VoiceWakePolicyCheck'])

    def test_cloud_cancellation_disconnects_and_blocks_next_request(self):
        settings = lab.settings()
        import os
        dependencies = os.pathsep.join(map(str,[settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-classpath', dependencies, '-d', directory,
                         *[lab.ROOT / 'app/src' / name for name in ('CloudClient.java', 'CloudConfig.java', 'AudioInput.java', 'AnswerPolicy.java', 'KnowledgeClient.java', 'KnowledgeRunState.java')],
                         lab.ROOT / 'tests/CloudCancellationCheck.java'])
            import os
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory + os.pathsep + dependencies,
                                 'dev.xr.rayneo.probe.CloudCancellationCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_glasses_opus_timing_and_wav_with_independent_reader(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'glasses.wav'
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-classpath', settings['android_jar'], '-d', directory,
                         *[lab.ROOT / 'app/src' / name for name in ('AudioInput.java', 'PcmWav.java', 'OpusAudio.java')],
                         lab.ROOT / 'tests/OpusAudioCheck.java'])
            import os
            lab.command([lab.tool(settings, 'java'), '-cp', directory + os.pathsep + str(settings['android_jar']),
                         'dev.xr.rayneo.probe.OpusAudioCheck', path])
            with wave.open(str(path), 'rb') as audio:
                self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()), (1, 2, 48000, 380160))
                self.assertEqual(audio.readframes(380160), bytes(i % 251 for i in range(760320)))

    def test_observed_unsafe_answer_is_withheld(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/AnswerPolicy.java', lab.ROOT / 'tests/AnswerPolicyCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.AnswerPolicyCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_recording_wav_with_independent_reader(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'recorded.wav'
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/AudioInput.java', lab.ROOT / 'app/src/PcmWav.java',
                         lab.ROOT / 'tests/PcmWavCheck.java'])
            lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.PcmWavCheck', path])
            with wave.open(str(path), 'rb') as audio:
                self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()), (1, 2, 16000, 128000))
                self.assertEqual(audio.readframes(128000), bytes(i % 251 for i in range(256000)))

    def test_actual_java_rejects_truncated_wav(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sample.wav'
            with wave.open(str(path), 'wb') as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(22050)
                audio.writeframes(b'\x00\x00' * 22050)
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/AudioInput.java', lab.ROOT / 'tests/AudioInputCheck.java'])
            checked = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                   'dev.xr.rayneo.probe.AudioInputCheck', path])
            self.assertIn(b'passed', checked.stdout)
