package android.os;
public final class SystemClock {
    private static long now;
    public static synchronized long elapsedRealtime(){return now+=500;}
}
