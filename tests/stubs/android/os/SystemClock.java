package android.os;
/** Virtual time for host-only interaction tests; never packaged in the app. */
public final class SystemClock {
    public static long now;
    public static long elapsedRealtime(){return now;}
}
