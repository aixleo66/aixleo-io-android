package dev.xr.rayneo.probe;

/** Narrow regression guard for an unsafe suggestion observed during the real voice test. */
final class AnswerPolicy {
    static boolean canDeliver(String text) {
        // Conservative: also withhold a warning quoting this combination. This is not a general safety classifier.
        String normalized = text.replaceAll("\\s+", "");
        return !(normalized.contains("塑料袋") && (normalized.contains("头") || normalized.contains("口鼻")));
    }
}
