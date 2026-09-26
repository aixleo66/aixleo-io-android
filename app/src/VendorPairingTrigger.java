package dev.xr.rayneo.probe;

import java.lang.reflect.*;

/** Uses the pinned SDK's encrypted pairing characteristic. Does not remove bonds,
 * change authentication data, or treat a write acknowledgement as pairing success. */
final class VendorPairingTrigger {
    interface Callback { void written(boolean success); }
    static void send(VendorRuntime runtime, String address, Callback callback) throws Exception {
        Object core = runtime.type("I3.A").getMethod("g", String.class).invoke(null, address);
        if (core == null) throw new IllegalStateException("Pairing transport unavailable");
        Object characteristics = runtime.type("I3.y").getField("u").get(core);
        if (characteristics == null || runtime.type("S3.i").getField("f").get(characteristics) == null)
            throw new IllegalStateException("Encrypted pairing characteristic unavailable");
        Object paired = null;
        for (Object candidate : runtime.type("S3.W").getEnumConstants())
            if ("PAIRED_CHAR".equals(((Enum<?>)candidate).name())) paired = candidate;
        if (paired == null) throw new IllegalStateException("Unsupported pairing characteristic mapping");
        Class<?> function = runtime.type("kotlin.jvm.functions.Function1");
        Constructor<?> constructor = runtime.type("I3.C").getConstructor(runtime.type("I3.y"), byte[].class,
            String.class, boolean.class, int.class, int.class, runtime.type("T3.a"), String.class,
            runtime.type("I3.b"), runtime.type("S3.p"), function, runtime.type("S3.l"), int.class,
            runtime.type("S3.W"), int.class, runtime.type("kotlin.jvm.internal.g"));
        // Same defaults and one-byte trigger as the SDK's WRITE_ENCRYPT_CHAR branch.
        Object message = constructor.newInstance(core, new byte[]{1}, address, true, 0, 0, null,
            java.util.UUID.randomUUID().toString(), null, null, null, null, 0, paired, 8048, null);
        Object peripheral = runtime.type("I3.y").getField("a").get(core);
        Object rate = runtime.type("I3.e0").getMethod("e").invoke(peripheral);
        if (rate == null) rate = runtime.type("S3.l").getField("m").get(null);
        Object sdkCompletion = runtime.type("V3.t").getConstructor(runtime.type("I3.y"),String.class).newInstance(core,address);
        Object completion = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{function}, (proxy, method, args) -> {
            if ("invoke".equals(method.getName())) {
                // Preserve the SDK's pairing-operation state transition on successful write.
                function.getMethod("invoke",Object.class).invoke(sdkCompletion,args[0]);
                callback.written(args != null && args.length == 1 && Boolean.TRUE.equals(args[0]));
                return runtime.type("ca.x").getField("a").get(null);
            }
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            return "PairingWriteCallback";
        });
        runtime.type("V3.c").getMethod("u", runtime.type("I3.C"), runtime.type("S3.l"), function)
            .invoke(null, message, rate, completion);
    }
}
