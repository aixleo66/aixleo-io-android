package dev.xr.rayneo.probe;

/** Main-thread command waits; an interrupt invalidates all older UI callbacks. */
final class CommandWait {
    private long generation;
    long begin() { return ++generation; }
    void invalidate() { ++generation; }
    boolean current(long ticket) { return ticket == generation; }
    static boolean interrupt(String kind) {
        return "stop".equals(kind) || "standby-off".equals(kind) || "record-stop".equals(kind);
    }
    static boolean allows(String kind, boolean busy, boolean pending) {
        return interrupt(kind) || (!busy && !pending);
    }
}
