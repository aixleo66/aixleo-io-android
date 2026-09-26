package dev.xr.rayneo.probe;

/** What the lens says when a round fails before an answer exists.
 *
 * <p>Until 2026-09-21 only the knowledge provider got a notice; every other failure called
 * {@code failStreaming}, which sends VOICE_ASSISTANT type 7 and no text at all. Measured
 * 2026-09-20: ASR returned the full question, the answer request to DeepSeek returned nothing for
 * 45.2 seconds -- exactly {@code CloudClient} 's read timeout -- and the round ended straight back
 * at standby. The user's account was "asked something, waited ten-odd seconds, then it closed by
 * itself", which is indistinguishable from a normal timed exit. The right branch already existed;
 * it was just spelled {@code "knowledge".equals(selectedProvider)}.
 *
 * <p>The text names what failed and what to do, and never blames the user's network without
 * evidence: the same round's ASR had gone through the cloud successfully, so "check your network"
 * would have been wrong advice. */
final class CloudFailureNotice {
    /** The knowledge wording that already shipped. Kept verbatim so the message a user may have
     * already seen does not change meaning between builds. */
    static final String KNOWLEDGE = "知识库暂时不可用，请稍后重试。";
    static final String TIMEOUT = "回答超时了，没能拿到结果。再说一次试试。";
    static final String CANCELLED = "这一轮已取消。";
    static final String GENERIC = "回答没能生成。再说一次试试。";

    /** A timeout is the failure worth distinguishing: it is the one the user experiences as a
     * long silence, and the one the official app retries ({@code maxTimeoutRetries=1}). */
    static boolean timedOut(String error) {
        if (error == null) return false;
        // "Read timed out" is what SocketTimeoutException actually carries as its message, and it
        // contains neither "Timeout" nor "timeout" -- the first version of this method missed the
        // very failure it was written for.
        String value = error.toLowerCase(java.util.Locale.ROOT);
        return value.contains("timeout") || value.contains("timed out") || error.contains("超时");
    }

    static boolean cancelled(String error) {
        if (error == null) return false;
        // Cancellation.check() throws InterruptedException("Cloud call cancelled"), so the text
        // that actually reaches here is lower case. Matching "Cancel" alone missed it.
        String value = error.toLowerCase(java.util.Locale.ROOT);
        return value.contains("interrupted") || value.contains("cancel") || error.contains("取消");
    }

    /** A short line for the lens. Never empty: an empty answer is refused upstream and would put
     * the round back on the silent path this exists to remove. */
    static String lensText(String provider, String error) {
        if ("knowledge".equals(provider)) return KNOWLEDGE;
        if (cancelled(error)) return CANCELLED;
        if (timedOut(error)) return TIMEOUT;
        return GENERIC;
    }

    // ---- Recognition-stage failures (before recognized text exists) ----------------
    //
    // Answer-stage handling only reaches failures after ASR produced text. Measured 2026-09-22 on two
    // consecutive rounds, both ended with the page closing and nothing said:
    //   * "识别连接异常：UnknownHostException" at +34ms, upload never started (network was off)
    //   * "本轮识别超时，已停止收音" at +20068ms, the last partial arrived 17.2s earlier and the
    //     stream went silent until the 20s hard cap in StreamingAsr
    // 282 rounds on the device: 26 failed, 18 of those with endpoint_reason=unknown, 8 hit the cap.

    /** Unlike the answer stage, a recognition-stage network error IS evidence about the network:
     * nothing reached the cloud at all. Saying so here is accurate, and the advice is actionable. */
    static final String ASR_UNREACHABLE = "连不上识别服务，检查网络后再说一次。";
    static final String ASR_SILENT = "没收到识别结果。再说一次试试。";
    static final String ASR_GENERIC = "识别没成功。再说一次试试。";

    /** Which recognition failures are worth saying out loud.
     *
     * <p>Not all of them: a follow-up window that simply ended without speech is normal behaviour,
     * not a fault, and 23 of the 282 measured rounds ended that way. Announcing it would train the
     * user to ignore the notice. */
    static boolean asrNoticeable(String reason) {
        if (reason == null || reason.trim().isEmpty()) return false;
        if (cancelled(reason)) return false;
        if (reason.contains("未检测到有效语音")) return false;
        // This has to be a WHITELIST. failStreaming is the single exit for every kind of round
        // failure, and the reason string is the only thing that tells them apart; the first
        // version of this method returned true for any non-empty reason and so claimed all of
        // them were recognition failures. Two real defects followed, both caught 2026-09-22 by
        // NativeAnswerIntegrationCheck once it could compile again:
        //   * "回答没有可显示文字" -- ANSWER stage, recognition worked fine and the model returned
        //     nothing to show. The lens said "识别没成功", which is false, and the notice text
        //     replaced the type 7 exit an empty answer is supposed to send.
        //   * a send failure reason of "failed" -- ran the notice path on top of the failure that
        //     was already being recorded, double-counting the round.
        // Both were live in the build the user tested on 2026-09-22. Only the two recognition
        // failures actually measured that day should reach the lens.
        return reason.contains("识别") || reason.contains("停止收音")
            || reason.contains("UnknownHost") || reason.contains("ConnectException")
            || reason.contains("NoRouteToHost") || reason.contains("连接异常");
    }

    static String asrLensText(String reason) {
        if (reason == null) return ASR_GENERIC;
        if (reason.contains("UnknownHost") || reason.contains("ConnectException")
            || reason.contains("NoRouteToHost") || reason.contains("连接异常")) return ASR_UNREACHABLE;
        if (timedOut(reason) || reason.contains("识别超时") || reason.contains("停止收音")) return ASR_SILENT;
        return ASR_GENERIC;
    }
}
