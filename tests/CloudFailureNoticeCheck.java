package dev.xr.rayneo.probe;

/** A round that fails before an answer exists must say so on the lens. */
final class CloudFailureNoticeCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // The exact failure measured 2026-09-20: CloudClient's read timeout elapsed with zero
        // bytes returned, and the round went straight back to standby without a word.
        check(CloudFailureNotice.timedOut("SocketTimeoutException"), "the measured failure was not recognised");
        check(CloudFailureNotice.timedOut("Read timed out"), "a lowercase timeout was missed");
        check(CloudFailureNotice.timedOut("回答超时"), "a Chinese timeout message was missed");
        check(!CloudFailureNotice.timedOut(null), "a null error must not be a timeout");
        check(!CloudFailureNotice.timedOut("HTTP 401"), "an auth failure was called a timeout");

        check(CloudFailureNotice.cancelled("InterruptedException"), "cancellation not recognised");
        check(CloudFailureNotice.cancelled("Cloud call cancelled"), "cancellation not recognised");
        check(!CloudFailureNotice.cancelled(null), "a null error must not be a cancellation");

        // Every path produces something readable. An empty notice would be refused by
        // sendNativeAnswer and put the round back on the silent path this exists to remove.
        for (String provider : new String[]{"deepseek", "knowledge", "aliyun", null}) {
            for (String error : new String[]{"SocketTimeoutException", "HTTP 500", "InterruptedException", null, ""}) {
                String text = CloudFailureNotice.lensText(provider, error);
                check(text != null && !text.trim().isEmpty(),
                    "empty notice for provider=" + provider + " error=" + error);
            }
        }

        // The knowledge wording already shipped; a user who has seen it must keep seeing the same
        // sentence rather than a reworded one.
        check(CloudFailureNotice.lensText("knowledge", "SocketTimeoutException").equals(CloudFailureNotice.KNOWLEDGE),
            "the knowledge notice changed");
        check(CloudFailureNotice.lensText("knowledge", null).equals(CloudFailureNotice.KNOWLEDGE),
            "the knowledge notice changed");

        // A timeout is the case the user experiences as a long silence, so it gets its own words.
        check(CloudFailureNotice.lensText("deepseek", "SocketTimeoutException").equals(CloudFailureNotice.TIMEOUT),
            "the DeepSeek timeout did not get the timeout notice");
        check(CloudFailureNotice.lensText("deepseek", "HTTP 500").equals(CloudFailureNotice.GENERIC),
            "a server error did not get the generic notice");
        check(CloudFailureNotice.lensText("deepseek", "InterruptedException").equals(CloudFailureNotice.CANCELLED),
            "a cancelled round did not get the cancelled notice");

        // Retry policy copied from the official app: maxTimeoutRetries=1, timeoutDelayMs=5000.
        check(CloudRetry.MAX_ATTEMPTS == 2, "attempt count no longer matches the official 1 retry");
        check(CloudRetry.DELAY_MS == 5000, "delay no longer matches the official timeoutDelayMs");
        check(CloudRetry.again(1, "SocketTimeoutException"), "an unanswered request was not repeated");
        check(!CloudRetry.again(2, "SocketTimeoutException"), "repeated more times than the official app");
        // Repeating these only doubles the wait before the same notice appears.
        check(!CloudRetry.again(1, "HTTP 401"), "a bad key was retried");
        check(!CloudRetry.again(1, "HTTP 500"), "a server error was retried");
        check(!CloudRetry.again(1, null), "an unknown failure was retried");
        // The round a cancellation belongs to is already gone; bringing it back would deliver an
        // answer to a user who walked away.
        check(!CloudRetry.again(1, "InterruptedException"), "a cancelled round was retried");
        check(!CloudRetry.again(1, "Cloud call cancelled"), "a cancelled round was retried");

        // asrNoticeable must be a whitelist. failStreaming is the single exit for every round
        // failure, so a blacklist claims every one of them is a recognition failure -- which is
        // what the first version did, shipping two defects on 2026-09-22 (see the method's note).
        // The two reasons actually measured that day:
        check(CloudFailureNotice.asrNoticeable("识别连接异常：UnknownHostException"),
              "the measured network failure reached the lens");
        check(CloudFailureNotice.asrNoticeable("本轮识别超时，已停止收音"),
              "the measured silent-ASR failure reached the lens");
        check(CloudFailureNotice.ASR_UNREACHABLE.equals(
                  CloudFailureNotice.asrLensText("识别连接异常：UnknownHostException")),
              "a network failure said so");
        check(CloudFailureNotice.ASR_SILENT.equals(
                  CloudFailureNotice.asrLensText("本轮识别超时，已停止收音")),
              "a silent stream said so");
        // Everything else must stay off it. These are answer-stage or transport failures: saying
        // "识别没成功" is false, and the notice also displaces the exit those paths owe.
        check(!CloudFailureNotice.asrNoticeable("回答没有可显示文字"),
              "an empty answer was reported as a recognition failure");
        check(!CloudFailureNotice.asrNoticeable("failed"),
              "a send failure was reported as a recognition failure");
        check(!CloudFailureNotice.asrNoticeable("HTTP 500"),
              "a server error was reported as a recognition failure");
        check(!CloudFailureNotice.asrNoticeable("SocketTimeoutException"),
              "an answer timeout was reported as a recognition failure");
        // A window that simply ended without speech is normal, not a fault.
        check(!CloudFailureNotice.asrNoticeable("续问候选未检测到有效语音"),
              "a silent follow-up window was announced as a failure");
        check(!CloudFailureNotice.asrNoticeable("Cloud call cancelled"),
              "a cancelled round was announced as a failure");
        check(!CloudFailureNotice.asrNoticeable(""), "an empty reason was announced");
        check(!CloudFailureNotice.asrNoticeable(null), "a null reason was announced");

        System.out.println("cloud failure notice checks passed");
    }
}
