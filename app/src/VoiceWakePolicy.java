package dev.xr.rayneo.probe;

/** iOS StandbyVoiceSession: type1 wakes from idle; type11 continues only a displayed answer. */
final class VoiceWakePolicy {
    static boolean accepts(int type, boolean armed, boolean recording, boolean answerDisplayed) {
        return armed && !recording && (type == 1 || (type == 11 && answerDisplayed));
    }
}
