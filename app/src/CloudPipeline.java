package dev.xr.rayneo.probe;

import java.io.*;
import java.util.Arrays;

/** One cancellation identity across local input, ASR and the optional next request. */
final class CloudPipeline {
    interface Source { byte[] read(CloudClient.Cancellation cancel) throws Exception; }
    interface Transcriber<R> { R run(byte[] audio, CloudClient.Cancellation cancel) throws Exception; }
    interface Answerer<R> { R run(R transcript, CloudClient.Cancellation cancel) throws Exception; }
    static final class VoiceResult<R> {
        final R asr, answer;
        VoiceResult(R asr, R answer) { this.asr = asr; this.answer = answer; }
    }

    static byte[] read(InputStream input, int limit, CloudClient.Cancellation cancel) throws Exception {
        if (input == null) throw new IOException("Audio input unavailable");
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            cancel.attachInput(stream);
            byte[] buffer = new byte[8192];
            try {
                while (true) {
                    cancel.check();
                    int n = stream.read(buffer);
                    cancel.check();
                    if (n < 0) return bytes.toByteArray();
                    if (n > limit - bytes.size()) throw new IOException("Audio input too large");
                    bytes.write(buffer, 0, n);
                }
            } finally { Arrays.fill(buffer, (byte)0); cancel.detachInput(stream); }
        }
    }

    static <R> R transcribe(CloudClient.Cancellation cancel, Source source, Transcriber<R> asr) throws Exception {
        byte[] audio = null;
        try {
            cancel.check();
            audio = source.read(cancel);
            cancel.check();
            R result = asr.run(audio, cancel);
            cancel.check();
            return result;
        } finally { if (audio != null) Arrays.fill(audio, (byte)0); }
    }

    static <R> VoiceResult<R> voice(CloudClient.Cancellation cancel, Source source,
                                  Transcriber<R> asr, Answerer<R> model) throws Exception {
        R transcript = transcribe(cancel, source, asr);
        cancel.check();
        R answer = model.run(transcript, cancel);
        cancel.check();
        return new VoiceResult<>(transcript, answer);
    }
}
