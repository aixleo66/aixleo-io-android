package dev.xr.rayneo.probe;

/** A failed or stale Activity must never release another Activity's live session. */
final class SessionOwnership {
    private Object owner;
    synchronized boolean claim(Object candidate) {
        if (candidate == null || (owner != null && owner != candidate)) return false;
        owner = candidate;
        return true;
    }
    synchronized boolean owns(Object candidate) { return candidate != null && owner == candidate; }
    synchronized boolean release(Object candidate) {
        if (!owns(candidate)) return false;
        owner = null;
        return true;
    }
}
