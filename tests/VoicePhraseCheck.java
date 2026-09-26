package dev.xr.rayneo.probe;

/** The explicit voice commands that were unreachable before 2026-09-20: the matchers held a few
 * fixed strings, so ordinary phrasings fell through to the model, which answered that it has no
 * such ability. */
final class VoicePhraseCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void todo(String text, String kind, String title) {
        VoiceTodoAction action = VoiceTodoAction.parse(text);
        if (kind == null) { check(action == null, "should not be a todo command: " + text); return; }
        check(action != null, "todo not recognised: " + text);
        check(kind.equals(action.kind), text + " -> kind " + action.kind + ", wanted " + kind);
        check(title.equals(action.title), text + " -> title [" + action.title + "], wanted [" + title + "]");
    }

    public static void main(String[] args) {
        // The user said this exact sentence and got "I cannot do that" back from the model.
        check(VoiceRecordingAction.matches("打开录音"), "打开录音 rejected");
        check(VoiceRecordingAction.matches("打开录音。"), "trailing punctuation rejected");
        check(VoiceRecordingAction.matches("帮我打开录音"), "leading filler rejected");
        check(VoiceRecordingAction.matches("开始录音"), "original phrasing lost");
        check(VoiceRecordingAction.matches("启动录音"), "original phrasing lost");
        check(VoiceRecordingAction.matches("录音"), "bare command rejected");
        check(VoiceRecordingAction.matches("开始录制"), "开始录制 rejected");
        // A question about the capability must never start a recording.
        check(!VoiceRecordingAction.matches("录音怎么用"), "question started a recording");
        check(!VoiceRecordingAction.matches("能打开录音吗"), "question started a recording");
        check(!VoiceRecordingAction.matches("打开录音功能是什么"), "question started a recording");
        check(!VoiceRecordingAction.matches("给我讲讲录音的历史"), "free speech started a recording");
        check(!VoiceRecordingAction.matches(""), "empty text started a recording");
        check(!VoiceRecordingAction.matches(null), "null text started a recording");

        // The user said this exact sentence and it reached the model as chat.
        todo("帮我加一个待办，明天下午三点开会", "create", "明天下午三点开会");
        todo("加个待办 买牛奶", "create", "买牛奶");
        todo("记一条待办 取快递", "create", "取快递");
        todo("新增任务 写周报", "create", "写周报");
        todo("创建一个事项 交电费", "create", "交电费");
        todo("添加待办 开会", "create", "开会");
        todo("完成待办 买牛奶", "complete", "买牛奶");
        todo("做完任务 写周报", "complete", "写周报");
        todo("查看待办", "list", "");
        todo("看看我的待办", "list", "");
        todo("我的任务有哪些", "list", "");
        // A trailing particle is not part of the task; storing it broke later exact matching.
        todo("添加待办 开会吧", "create", "开会");
        todo("帮我加一个待办，买牛奶啦", "create", "买牛奶");
        // Questions and free speech are still refused.
        todo("添加待办的功能是什么", null, null);
        todo("待办怎么用", null, null);
        todo("今天天气怎么样", null, null);
        todo("添加待办", "invalid", "");

        // The official assistant closes its dialog on this; ours had no path at all.
        check(VoiceExitPhrase.matches("退出"), "退出 rejected");
        check(VoiceExitPhrase.matches("退出。"), "trailing punctuation rejected");
        check(VoiceExitPhrase.matches("退出吧"), "trailing particle rejected");
        check(VoiceExitPhrase.matches("退出，谢谢"), "trailing politeness rejected");
        check(VoiceExitPhrase.matches("关闭对话"), "关闭对话 rejected");
        check(VoiceExitPhrase.matches("关闭页面"), "关闭页面 rejected");
        check(VoiceExitPhrase.matches("结束对话"), "结束对话 rejected");
        check(VoiceExitPhrase.matches("帮我退出"), "leading filler rejected");
        // Mis-firing destroys an answer the user is still reading, so questions must not match.
        check(!VoiceExitPhrase.matches("怎么退出"), "question closed the page");
        check(!VoiceExitPhrase.matches("可以退出吗"), "question closed the page");
        check(!VoiceExitPhrase.matches("退出这个应用要怎么做"), "free speech closed the page");
        check(!VoiceExitPhrase.matches("给我讲讲退出机制"), "free speech closed the page");
        check(!VoiceExitPhrase.matches(""), "empty text closed the page");
        check(!VoiceExitPhrase.matches(null), "null text closed the page");
        // Independent review 2026-09-20: these bare words are ordinary answers to the assistant
        // or commands aimed at something else. Matching one destroys an answer mid-read.
        check(!VoiceExitPhrase.matches("不用了"), "a plain refusal closed the page");
        check(!VoiceExitPhrase.matches("没事了"), "a plain refusal closed the page");
        check(!VoiceExitPhrase.matches("关闭"), "a bare 关闭 closed the page");
        check(!VoiceExitPhrase.matches("关掉"), "a bare 关掉 closed the page");
        check(!VoiceExitPhrase.matches("结束"), "a bare 结束 closed the page");
        check(!VoiceExitPhrase.matches("关闭WiFi"), "关闭WiFi closed the page");
        check(!VoiceExitPhrase.matches("结束会议"), "结束会议 closed the page");

        // Independent review 2026-09-20: the question guard ran on the extracted title only and
        // matched leading forms, so these wrote real todos with nonsense titles.
        todo("新建任务为什么失败", null, null);
        todo("加任务失败怎么办", null, null);
        todo("创建任务要注意什么", null, null);
        todo("完成任务有什么奖励", null, null);
        todo("完成任务了", "invalid", "");
        // Alternation is leftmost-first, so a short noun ahead of a long one cut the compound.
        todo("帮我加一个待办事项，明天下午三点开会", "create", "明天下午三点开会");
        todo("加一条备忘 买菜", "create", "买菜");
        // Recording keeps working after the shared normalizer gained particle trimming.
        check(VoiceRecordingAction.matches("打开录音吧"), "trailing particle rejected");

        // Real round 2026-09-20: ASR glued a filler onto the command with no separator, so the
        // closed set missed it and the user had to repeat themselves.
        check(VoiceRecordingAction.matches("嗯呐打开录音"), "glued leading filler rejected");
        check(VoiceRecordingAction.matches("打开录音呐"), "glued trailing filler rejected");
        check(VoiceRecordingAction.matches("嗯打开录音"), "glued leading filler rejected");
        check(VoiceExitPhrase.matches("嗯退出"), "glued leading filler rejected");
        todo("嗯呐加个待办 买菜", "create", "买菜");
        // Stripping fillers must not eat a real command or title.
        check(!VoiceRecordingAction.matches("啊啊啊"), "all-filler started a recording");
        todo("添加待办 哦开会", "create", "哦开会");

        // Real round 2026-09-20: the user re-woke with the wake word and said nothing more. The
        // round still reached the model, which restated the previous answer from history, so the
        // todo receipt was printed on the lens a second time.
        check(VoicePhrase.isDegenerate("小雷小雷"), "wake word alone reached the model");
        check(VoicePhrase.isDegenerate("小雷"), "wake word alone reached the model");
        check(VoicePhrase.isDegenerate(""), "empty text reached the model");
        check(VoicePhrase.isDegenerate(null), "null text reached the model");
        // A bare filler is deliberately NOT degenerate. Independent review showed the old
        // two-code-point floor also rejected "好" "对" "行" "谁？" -- real things the user said.
        // Letting one stray "嗯" reach the model costs a pointless reply; dropping a real
        // answer costs the user their turn. The wake-word strip still covers the case that
        // produced the duplicate print.
        check(!VoicePhrase.isDegenerate("嗯"), "a bare filler was treated as silence");
        check(!VoicePhrase.isDegenerate("好"), "a real one-word answer was dropped");
        check(!VoicePhrase.isDegenerate("对"), "a real one-word answer was dropped");
        check(!VoicePhrase.isDegenerate("谁？"), "a real short question was dropped");
        check(VoicePhrase.isDegenerate("。"), "punctuation alone reached the model");
        check(VoicePhrase.isDegenerate("小雷小雷。"), "wake word alone reached the model");
        // Anything the user actually asked must still reach the model or its action.
        check(!VoicePhrase.isDegenerate("天气"), "a real two-character question was dropped");
        check(!VoicePhrase.isDegenerate("退出"), "an exit request was dropped");
        check(!VoicePhrase.isDegenerate("一加一等于几"), "a real question was dropped");
        check(!VoicePhrase.isDegenerate("小雷小雷，今天天气怎么样"), "wake word plus a question was dropped");

        // The three commands must stay mutually exclusive: one utterance, one action.
        String[] samples = {"打开录音", "添加待办 开会", "退出", "今天天气怎么样"};
        for (String sample : samples) {
            int hits = 0;
            if (VoiceRecordingAction.matches(sample)) hits++;
            if (VoiceTodoAction.parse(sample) != null) hits++;
            if (VoiceExitPhrase.matches(sample)) hits++;
            check(hits <= 1, "ambiguous utterance matched " + hits + " commands: " + sample);
        }

        System.out.println("voice phrase checks passed");
    }
}
