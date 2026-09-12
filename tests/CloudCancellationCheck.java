package dev.xr.rayneo.probe;
import java.net.URL;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
final class CloudCancellationCheck {
    static final class Fake extends HttpsURLConnection {
        volatile int closes;
        Fake() throws Exception { super(new URL("https://api.deepseek.com/chat/completions")); }
        public void disconnect() { closes++; }
        public boolean usingProxy() { return false; }
        public void connect() { throw new AssertionError("Test must not access network"); }
        public String getCipherSuite() { return "test"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return null; }
    }
    public static void main(String[] args) throws Exception {
        CloudClient.Cancellation before = new CloudClient.Cancellation(); before.cancel();
        try { before.attach(new Fake()); throw new AssertionError("Cancelled request attached"); }
        catch (InterruptedException expected) {}
        CloudClient.Cancellation running = new CloudClient.Cancellation(); Fake active = new Fake(); running.attach(active); running.cancel();
        long deadline = System.nanoTime() + 1000000000L;
        while (active.closes == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        if (active.closes != 1) throw new AssertionError("Active connection not closed");
        try { running.check(); throw new AssertionError("Next model request would continue"); }
        catch (InterruptedException expected) {}
        CloudClient.Cancellation completed = new CloudClient.Cancellation(); Fake done = new Fake(); completed.attach(done); completed.detach(done); completed.cancel();
        if (done.closes != 0) throw new AssertionError("Detached connection closed");
        System.out.println("Cancellation before upload, during request, and after detach passed");
    }
}
