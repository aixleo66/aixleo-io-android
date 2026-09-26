package dev.xr.rayneo.probe;

/** Whether a completed utterance is the user asking to close the answer page.
 *
 * <p>The official assistant was observed on 2026-09-20 closing its dialog when the user says
 * "退出". Ours had no such path: every utterance went to the model as chat, so the model
 * answered that it cannot do that.
 *
 * <p>Explicit closed set only, matched after stripping bounded leading fillers and trailing
 * punctuation. A question about exiting is never an exit request, and nothing is inferred from
 * free speech -- the same discipline as {@link VoiceTodoAction} and {@link VoiceRecordingAction}.
 * Mis-firing here destroys an answer the user is still reading, so the set stays small. */
final class VoiceExitPhrase {
    static boolean matches(String text) {
        String value = VoicePhrase.trimTrailingInterjection(VoicePhrase.normalize(text));
        if (value.isEmpty() || VoicePhrase.isQuestion(value)) return false;
        // Bare "关闭" "关掉" "结束" "不用了" "没事了" were removed after independent
        // review: they are ordinary answers to the assistant ("需要我继续吗" -> "不用了") and
        // ordinary commands aimed at something else (closing a song, an alarm, a recording).
        // A false match destroys an answer the user is still reading and never reaches the model,
        // so the set keeps only phrasings that can only mean closing this dialog.
        return value.equals("退出") || value.equals("退出对话") || value.equals("退出助手")
            || value.equals("退出页面") || value.equals("退出一下") || value.equals("退下")
            || value.equals("关闭对话") || value.equals("关闭助手") || value.equals("关闭页面")
            || value.equals("结束对话") || value.equals("结束助手") || value.equals("关掉对话");
    }
}
