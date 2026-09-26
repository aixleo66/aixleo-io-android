"""09-23: recordings are saved as Ogg Opus (official AudioConvertUtil behaviour), not decoded to WAV."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import lab

CAPTURE = Path(os.environ['RAYNEO_OPUS_CAPTURE']) if os.environ.get('RAYNEO_OPUS_CAPTURE') else None


class OggOpusWriterChecks(unittest.TestCase):
    def test_container_structure(self):
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/OggOpusWriter.java', lab.ROOT / 'tests/OggOpusWriterCheck.java'])
            out = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.OggOpusWriterCheck']).stdout
        self.assertIn(b'ogg opus writer checks passed', out)

    def test_recording_folder_audio_choice_and_gap_note(self):
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/RecordingAudio.java', lab.ROOT / 'tests/RecordingAudioCheck.java'])
            out = lab.command([lab.tool(s, 'java'), '-Dfile.encoding=UTF-8', '-cp', tmp, 'dev.xr.rayneo.probe.RecordingAudioCheck']).stdout
        self.assertIn(b'recording audio checks passed', out)

    def test_real_packets_decode_with_an_independent_decoder(self):
        # The structure check uses synthetic packets, so here real glasses packets are wrapped (with one
        # packet removed) and decoded by ffmpeg. Needs an explicitly supplied
        # capture and ffmpeg; otherwise this optional test is skipped.
        ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
        if not ffmpeg or not ffprobe or CAPTURE is None or not CAPTURE.is_file():
            self.skipTest('ffmpeg/ffprobe or RAYNEO_OPUS_CAPTURE is not available on this machine')
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            head = Path(tmp) / 'head.rawopus'
            with CAPTURE.open('rb') as f:
                head.write_bytes(f.read(240 * 3000))  # first 60 s
            driver = Path(tmp) / 'Wrap.java'
            driver.write_text(
                'package dev.xr.rayneo.probe;import java.io.*;import java.util.*;public final class Wrap{'
                'public static void main(String[] a)throws Exception{TreeMap<Integer,Integer> r=new TreeMap<>();'
                'r.put(0,240*1000);r.put(240*1001,240*3000);'
                'System.out.println(OggOpusWriter.wrap(new File(a[0]),r,new File(a[1])).silenceFilled);}}', encoding='utf-8')
            lab.command([lab.tool(s, 'javac'), '-encoding', 'UTF-8', '-d', tmp, lab.ROOT / 'app/src/OggOpusWriter.java', driver])
            ogg = Path(tmp) / 'head.ogg'
            out = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.Wrap', str(head), str(ogg)]).stdout
            self.assertEqual(out.strip(), b'1')
            decoded = subprocess.run([ffmpeg, '-v', 'error', '-i', str(ogg), '-f', 'null', '-'], capture_output=True, timeout=60)
            self.assertEqual(decoded.returncode, 0, decoded.stderr)
            self.assertEqual(decoded.stderr.strip(), b'', 'decoder reported errors')
            duration = subprocess.run([ffprobe, '-v', 'error', '-show_entries', 'format=duration',
                                       '-of', 'default=nw=1:nk=1', str(ogg)], capture_output=True, timeout=60).stdout
            self.assertAlmostEqual(float(duration), 60.0, delta=0.05)


if __name__ == '__main__':
    unittest.main()
