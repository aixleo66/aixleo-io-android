package dev.xr.rayneo.probe;

/** Phone text and lens text always belong to the same completed answer. */
final class AnswerResult {
    final String text, lensText;
    AnswerResult(String text, String lensText) {
        this.text = text == null ? "" : text;
        this.lensText = lensText == null || lensText.isEmpty() ? this.text : lensText;
    }
    static AnswerResult empty() { return new AnswerResult("", ""); }
    boolean available() { return !text.isEmpty() && !lensText.isEmpty(); }
}
