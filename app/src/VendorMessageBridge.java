package dev.xr.rayneo.probe;

import android.os.Handler;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Original plugin listener + original EventChannel sink, without a Flutter engine. */
final class VendorMessageBridge implements AutoCloseable {
    interface Receiver { void event(Map<?, ?> value); void error(Throwable error); }
    private final VendorRuntime runtime;
    private final Object plugin, originalListener;
    private final List<?> listeners;
    private final AtomicReference<Object> active;
    private static Object field(Object value, String name) throws Exception { return value.getClass().getField(name).get(value); }

    @SuppressWarnings("unchecked")
    VendorMessageBridge(VendorRuntime runtime, android.content.Context context, Handler handler, Receiver receiver) throws Exception {
        this.runtime = runtime;
        Class<?> pluginType = runtime.type("com.rayneo.rayneo_venus_sdk_plugin.j");
        plugin = pluginType.getConstructor().newInstance();
        pluginType.getField("b").set(plugin, context.getApplicationContext());
        Object codec = runtime.type("G9.D").getField("b").get(null);
        Class<?> messengerType = runtime.type("G9.j");
        Object messenger = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{messengerType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "ProbeEventMessenger";
            }
            if (method.getName().equals("f") && args != null && args[0] instanceof ByteBuffer) {
                try {
                    ByteBuffer encoded = ((ByteBuffer)args[0]).duplicate(); encoded.flip();
                    if (encoded.remaining() > 262144) throw new IllegalArgumentException("Event too large");
                    Object value = codec.getClass().getMethod("c", ByteBuffer.class).invoke(codec, encoded);
                    if (value instanceof Map) handler.post(() -> receiver.event((Map<?, ?>)value));
                } catch (Throwable e) { handler.post(() -> receiver.error(e)); }
            }
            return null;
        });
        Object channel = runtime.type("G9.m").getConstructor(messengerType, String.class).newInstance(messenger, "probe/events");
        Object stream = runtime.type("G9.m$a").getConstructor(runtime.type("G9.m"), runtime.type("G9.n")).newInstance(channel, plugin);
        Constructor<?> sinkConstructor = runtime.type("G9.m$a$a").getDeclaredConstructor(runtime.type("G9.m$a"));
        sinkConstructor.setAccessible(true);
        Object sink = sinkConstructor.newInstance(stream);
        active = (AtomicReference<Object>)field(stream, "b"); active.set(sink);
        pluginType.getMethod("onListen", Object.class, runtime.type("G9.l")).invoke(plugin, null, sink);
        Object registry = field(runtime.core, "c");
        listeners = (List<?>)field(registry, "a");
        originalListener = field(plugin, "F");
        registry.getClass().getMethod("a", Object.class).invoke(registry, originalListener);
        if (!listeners.contains(originalListener)) throw new IllegalStateException("Original message listener absent");
    }
    public void close() throws Exception {
        listeners.remove(originalListener);
        active.set(null);
        plugin.getClass().getMethod("onCancel", Object.class).invoke(plugin, new Object[]{null});
        ((Handler)field(plugin, "e")).getLooper().quitSafely();
        if (listeners.contains(originalListener)) throw new IllegalStateException("Message listener cleanup failed");
    }
}
