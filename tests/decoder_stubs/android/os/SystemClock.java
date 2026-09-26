package android.os;
/** Dedicated decoder-test clock; never packaged in the application. */
public final class SystemClock {
    public static volatile long now;
    public static long elapsedRealtime() { return now; }
}
