package dev.xr.rayneo.probe;

public final class SessionOwnershipCheck {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        SessionOwnership sessions = new SessionOwnership();
        Object first = new Object(), second = new Object();
        check(!sessions.claim(null));
        check(sessions.claim(first));
        check(!sessions.claim(second));
        check(!sessions.release(second)); // Rejected Activity's onDestroy must not end the first session.
        check(sessions.owns(first));
        check(sessions.release(first));
        check(sessions.claim(second));
        check(!sessions.release(first)); // Late cleanup from a completed Activity must not end its successor.
        check(sessions.owns(second));
        check(sessions.release(second));
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[16];
        for (int i=0;i<threads.length;i++) threads[i]=new Thread(() -> {
            try { go.await(); if (sessions.claim(new Object())) winners.incrementAndGet(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        });
        for (Thread thread:threads) thread.start();
        go.countDown();
        for (Thread thread:threads) thread.join();
        check(winners.get()==1);
        System.out.println("session ownership checks passed");
    }
}
