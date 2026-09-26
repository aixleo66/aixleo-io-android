package dev.xr.rayneo.probe;

/** Shared text preparation for the explicit voice commands.
 *
 * <p>{@link VoiceTodoAction}, {@link VoiceRecordingAction} and {@link VoiceExitPhrase} each need
 * the same two steps before matching: strip the bounded fillers that final ASR joins onto the
 * front of a command, and refuse anything shaped like a question. Every defect fixed on
 * 2026-09-20 came from one piece of logic living in two places and only one copy being updated,
 * so these live here once. */
final class VoicePhrase {
    /** Fillers that may be followed by a separator. These include real words ("对" "好" "这个"),
     * so they are only stripped when punctuation or whitespace proves they were a standalone
     * acknowledgement. */
    private static final String SEPARATED =
        "(?:对了|好了|行了|那好|好吧|对|好的|好|是的|那个|这个|就是"
        + "|嗯呐|嗯嗯|嗯|额|呃|唔|哎|唉|欸|啊|哦|噢)";
    /** Fillers that ASR glues straight onto a command with no separator at all. Only pure
     * interjections belong here: independent review found the wider set eating real content --
     * "对账提醒" became "账提醒" and "就是明天开会" became "明天开会". */
    private static final String GLUED = "(?:嗯呐|嗯嗯|嗯|呃|唔|欸)";

    /** Trailing punctuation and bounded leading fillers removed; never scans past free speech. */
    static String normalize(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replaceAll("[。！!,，、\\s]+$", "").trim();
        // Final ASR joins acknowledgements onto the front of a command, sometimes with a separator
        // and sometimes glued straight on: a real round on 2026-09-20 produced "嗯呐打开录音",
        // which missed the closed set because the old rule required punctuation after the filler.
        // Two passes: acknowledgements stack ("哦对，打开录音", "嗯对，打开录音"), and a single
        // pass leaves the second one plus its separator in front of the command.
        for (int pass = 0; pass < 2; pass++) {
            // Two acknowledgements can run together with only the second carrying a separator
            // ("哦对，打开录音"). Strip the first only when the second is provably standalone,
            // otherwise "好好休息" would lose a character it actually needs.
            value = value.replaceFirst(
                "^(?:" + SEPARATED + ")(?=" + SEPARATED + "[。！!，,、\\s])", "");
            value = value.replaceFirst("^(?:" + SEPARATED + "[。！!，,、\\s]+){1,3}", "");
            value = value.replaceFirst("^(?:" + GLUED + "){1,2}(?=.)", "");
            // Stripping a filler can leave its separator behind, which fails the closed sets just
            // as surely as the filler did.
            value = value.replaceFirst("^[。！!，,、\\s]+", "");
        }
        value = value.replaceFirst("^(?:麻烦你|麻烦|请帮我|帮我|请|我要|我想|给我)[，,、\\s]*", "");
        value = value.replaceFirst("(?:[，,、\\s]*(?:谢谢|多谢))+$", "");
        return trimParticles(value.trim());
    }

    /** A question about a capability is never a request to perform it. */
    static boolean isQuestion(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.matches(".*[？?].*") || value.endsWith("吗") || value.endsWith("呢")) return true;
        // The interrogative may sit anywhere, not just at the front. Independent review found
        // "新建任务为什么失败" creating a real todo titled "为什么失败": the guard ran on the
        // extracted title and only matched leading forms. Failing closed sends the sentence to the
        // model, which is the right outcome for a question; failing open writes junk.
        return value.matches(".*(?:是什么|为什么|什么时候|怎么办|怎么样|怎么|如何|是否|能否"
            + "|能不能|可不可以|会不会|有什么|有没有|哪些|哪个|多少|要注意).*");
    }

    /** Nothing was actually said: only the wake word, or nothing at all.
     *
     * <p>Observed 2026-09-20: the user re-woke with "小雷小雷" and said nothing else. The round
     * reached the model anyway, which restated the previous answer from history, so the todo
     * receipt was printed a second time. The database confirmed no duplicate task was written.
     *
     * <p>The test is emptiness, not a length floor. A floor of two code points rejected "好" "对"
     * "行" "谁？" -- ordinary short answers and questions that the user really did say. */
    static boolean isDegenerate(String text) {
        String value = normalize(text);
        value = value.replaceAll("^(?:小雷)+", "").replaceAll("[。！!，,、？?\\s]+", "").trim();
        return value.isEmpty();
    }

    /** Trailing modal particles carry no meaning and must not become part of a stored title
     * ("开会吧" was stored verbatim and then never matched on completion).
     *
     * <p>Deliberately excludes 额 呐 啊 嗯 呃: they are ordinary characters inside real titles.
     * Independent review found "添加待办 查话费余额" being stored as "查话费余", after which the
     * user could never complete that task by voice because completion matches the title exactly. */
    static String trimParticles(String value) {
        if (value == null) return "";
        return value.replaceAll("(?:吧|啦|呀|哇|哦|喔|嘞|咯|喽|嘛|了)+$", "").trim();
    }

    /** Trailing interjections for the fixed command sets only, never for a stored title. */
    static String trimTrailingInterjection(String value) {
        if (value == null) return "";
        return value.replaceAll("(?:呐|啊|嗯|呃|额|哦|噢|嘞)+$", "").trim();
    }
}
