package dev.xr.rayneo.probe;

import android.os.Handler;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Reads bounded envelope metadata before the original Flutter plugin consumes audio. */
final class VendorChannelObserver implements AutoCloseable {
    interface Receiver {
        void metadata(String device, String business, String route, BusinessEnvelope wire);
        void error(Throwable error);
        default boolean wantsAudio() { return false; }
        default boolean wantsRecording() { return false; }
    }
    private final Object observer;
    private final List<?> slots;
    private final Set<List<?>> channels = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<List<?>, Boolean>()));
    private final Set<String> devices = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean closed;
    private static Object field(Object owner, String name) throws Exception { return owner.getClass().getField(name).get(owner); }

    VendorChannelObserver(VendorRuntime runtime, String targetAddress, Handler handler, Receiver receiver) throws Exception {
        Class<?> protocol = runtime.type("I3.g");
        Object relay = runtime.type("S3.w").getMethod("valueOf", String.class).invoke(null, "RELAY");
        Class<?> strategy = runtime.type("S3.H");
        Object routes = Array.newInstance(strategy, 2);
        Array.set(routes, 0, strategy.getMethod("valueOf", String.class).invoke(null, "LOW_BLE"));
        Array.set(routes, 1, strategy.getMethod("valueOf", String.class).invoke(null, "HIGH_SPP"));
        observer = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{protocol}, (proxy, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == args[0];
                return "LabEnvelopeObserver";
            }
            if (name.equals("b")) return relay;
            if (name.equals("e")) return routes;
            if (closed) return null;
            try {
                if (name.equals("a")) {
                    Object per = field(args[0], "b");
                    boolean match = false;
                    for (String key : new String[]{"n", "o", "f"}) if (targetAddress.equalsIgnoreCase(String.valueOf(field(per, key)))) match = true;
                    if (match) {
                        devices.add(String.valueOf(field(args[0], "a")));
                        channels.add((List<?>)field(args[1], "h"));
                    }
                } else if (name.equals("d") || name.equals("c")) {
                    Object packet = args[name.equals("d") ? 0 : 1];
                    String device = String.valueOf(field(packet, "a"));
                    if (!devices.contains(device) || !"NOERROR".equals(String.valueOf(field(packet, "q")))) return null;
                    String business = String.valueOf(field(packet, "p"));
                    if (!business.equals("VOICE_ASSISTANT") && !business.equals("LAUNCHER") && !business.equals("RECORDING_SERVICE")) return null;
                    byte[] bytes = (byte[])field(packet, "i");
                    BusinessEnvelope wire = business.equals("RECORDING_SERVICE") ? BusinessEnvelope.decodeRecording(bytes,receiver.wantsRecording()) : BusinessEnvelope.decode(bytes, business.equals("VOICE_ASSISTANT") && receiver.wantsAudio());
                    String route = String.valueOf(field(packet, "h"));
                    if (queued.incrementAndGet() > 64) {
                        queued.decrementAndGet();
                        if (wire.audio != null) Arrays.fill(wire.audio, (byte)0);
                        throw new IllegalStateException("Channel metadata queue overflow");
                    }
                    // Audio is copied only for an explicitly armed cloud capture, never for metadata tests.
                    handler.post(() -> {
                        try { if (!closed) receiver.metadata(device, business, route, wire); }
                        finally { if (wire.audio != null) Arrays.fill(wire.audio, (byte)0); queued.decrementAndGet(); }
                    });
                }
            } catch (Throwable error) { handler.post(() -> { if (!closed) receiver.error(error); }); }
            return null;
        });
        Object companion = runtime.type("I3.e").getField("c").get(null);
        Object router = companion.getClass().getMethod("a").invoke(companion);
        slots = (List<?>)field(router, "a");
        runtime.type("I3.f").getMethod("a", protocol).invoke(null, observer);
        if (!slots.contains(observer)) throw new IllegalStateException("Channel observer not installed");
    }
    public void close() {
        closed = true;
        slots.remove(observer);
        synchronized (channels) { for (List<?> list : channels) list.remove(observer); channels.clear(); }
        devices.clear();
    }
}
