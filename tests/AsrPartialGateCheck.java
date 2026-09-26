package dev.xr.rayneo.probe;

import java.util.ArrayList;
import java.util.List;

/** Offline acceptance for the partial-text throttle. Frames arrive the way the recognizer
 * delivers them: one every 100ms, each a longer take on the same sentence. */
final class AsrPartialGateCheck {
    private static List<String> drive(AsrPartialGate gate, String[] texts, long[] times) {
        List<String> released = new ArrayList<>();
        for (int i = 0; i < texts.length; i++)
            if (gate.accept(texts[i], times[i])) { gate.commit(texts[i], times[i]); released.add(texts[i]); }
        return released;
    }
    private static List<String> typing(AsrPartialGate gate, String sentence, long start, int everyMs) {
        String[] texts = new String[sentence.length()];
        long[] times = new long[sentence.length()];
        for (int i = 0; i < sentence.length(); i++) {
            texts[i] = sentence.substring(0, i + 1);
            times[i] = start + (long) (i + 1) * everyMs;
        }
        return drive(gate, texts, times);
    }
    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        final long t0 = 100000;

        // 1. An ordinary sentence lands inside the 3-5 pushes the official app was measured at.
        List<String> ordinary = typing(new AsrPartialGate(4, 1200, 12), "今天天气怎么样啊真好呀", t0, 100);
        require(ordinary.size() >= 3 && ordinary.size() <= 5,
            "ordinary sentence released " + ordinary.size() + " times, expected 3-5: " + ordinary);

        // 2. Each push extends the last, so nothing already on the glasses is rewritten.
        for (int i = 1; i < ordinary.size(); i++)
            require(ordinary.get(i).startsWith(ordinary.get(i - 1)),
                "push " + i + " rewrote text already shown: " + ordinary);

        // 3. A long sentence keeps streaming instead of stalling once the cap is near.
        String longer = "帮我查一下明天从北京出发到上海的高铁票还有没有二等座余票谢谢";
        List<String> streamed = typing(new AsrPartialGate(4, 1200, 12), longer, t0, 100);
        require(streamed.size() >= 6, "long sentence released only " + streamed.size() + " times");
        require(streamed.get(streamed.size() - 1).length() >= longer.length() - 4,
            "long sentence stopped updating well before the end: " + streamed.get(streamed.size() - 1));

        // 4. A revision of text already shown waits instead of jittering it.
        List<String> revised = drive(new AsrPartialGate(4, 1200, 12),
            new String[]{"今天天气怎", "今天天气怎么样", "金天天气怎么样啊", "金天天气怎么样啊真"},
            new long[]{t0 + 100, t0 + 200, t0 + 300, t0 + 400});
        require(revised.size() == 1 && revised.get(0).equals("今天天气怎"),
            "a revised prefix went straight through: " + revised);

        // 5. That revision still arrives once the recognizer has gone quiet.
        List<String> settled = drive(new AsrPartialGate(4, 1200, 12),
            new String[]{"今天天气怎", "金天天气怎么样", "金天天气怎么样"},
            new long[]{t0 + 100, t0 + 300, t0 + 1600});
        require(settled.size() == 2 && settled.get(1).equals("金天天气怎么样"),
            "a silent gap failed to flush the corrected text: " + settled);

        // 6. Identical text is never pushed twice, the per-round cap holds, and rounds are independent.
        require(drive(new AsrPartialGate(4, 1200, 12), new String[]{"今天", "今天", "今天"},
            new long[]{t0 + 100, t0 + 200, t0 + 300}).size() == 1, "identical text pushed more than once");
        require(typing(new AsrPartialGate(4, 1200, 2), "今天天气怎么样啊真好呀", t0, 100).size() == 2,
            "per-round cap not enforced");
        require(typing(new AsrPartialGate(4, 1200, 2), "今天天气怎么样啊真好呀", t0, 100).size() == 2,
            "a fresh round did not start from a clean cap");

        System.out.println("passed");
    }
}
