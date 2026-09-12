package dev.xr.rayneo.probe;

import android.os.Handler;
import java.lang.reflect.*;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.*;

/** Uses the SDK's logger interface; only exports route metadata for our own queued messages. */
final class VendorRouteObserver implements AutoCloseable {
    interface Receiver { void sent(String route, String packet, String request); }
    private final Class<?> logger;
    private final Object previous, observer;
    private final boolean enabled;
    private final int level;
    private volatile boolean closed;
    VendorRouteObserver(VendorRuntime runtime, Supplier<String> deviceId, Handler handler, Receiver receiver) throws Exception {
        logger = runtime.type("c4.i"); previous = logger.getField("e").get(null);
        enabled = logger.getField("b").getBoolean(null); level = logger.getField("d").getInt(null);
        Pattern pattern = Pattern.compile("^Send\\[(BLE|SPP)\\] msgId=(\\d+), ack=[01], dev=(.+)$");
        Class<?> protocol = runtime.type("T3.m");
        observer = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{protocol}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return "LabRouteMetadata";
            }
            // Never forward, retain or print SDK payload/account/crypto logs.
            if (closed || args == null || args.length != 4 || !"RNChannelImpl".equals(args[1]) || !(args[2] instanceof String)) return null;
            Matcher match = pattern.matcher((String)args[2]);
            if (!match.matches() || !match.group(3).equals(deviceId.get())) return null;
            try {
                Object core = runtime.type("I3.A").getMethod("f", String.class).invoke(null, match.group(3));
                Map<?,?> pending = (Map<?,?>)core.getClass().getField("t").get(core);
                Object message = pending.get(match.group(2));
                if (message == null) return null;
                String request = (String)message.getClass().getField("a").get(message);
                if (!(request.equals("general-status") || request.startsWith("voice-") || request.startsWith("status-"))) return null;
                String route = match.group(1), packet = match.group(2);
                handler.post(() -> { if (!closed) receiver.sent(route, packet, request); });
            } catch (Exception ignored) { /* Missing correlation is not evidence of a route. */ }
            return null;
        });
        logger.getField("e").set(null, observer); logger.getField("d").setInt(null, 3); logger.getField("b").setBoolean(null, true);
    }
    public void close() throws Exception {
        closed = true;
        if (logger.getField("e").get(null) == observer) {
            logger.getField("e").set(null, previous); logger.getField("d").setInt(null, level); logger.getField("b").setBoolean(null, enabled);
        }
    }
}
