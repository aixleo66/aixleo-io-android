package dev.xr.rayneo.probe;
final class VoiceWakePolicyCheck {
    public static void main(String[] args) {
        // Captured hardware sequence: answer displayed -> type11. This must start exactly one round.
        if (!VoiceWakePolicy.accepts(11, true, false, true)) throw new AssertionError("Displayed answer continuation lost");
        if (VoiceWakePolicy.accepts(11, true, true, true)) throw new AssertionError("Duplicate next-turn starts recording twice");
        if (VoiceWakePolicy.accepts(11, true, false, false)) throw new AssertionError("Type11 started from ordinary idle");
        // The other observed sequence closes the old page (type8), then sends ordinary wake type1.
        if (VoiceWakePolicy.accepts(8, true, false, true)) throw new AssertionError("Exit treated as wake");
        if (!VoiceWakePolicy.accepts(1, true, false, false)) throw new AssertionError("Fresh wake after old page exit lost");
        if (VoiceWakePolicy.accepts(1, false, false, false)) throw new AssertionError("Disabled service recorded");
    }
}
