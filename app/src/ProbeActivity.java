package dev.xr.rayneo.probe;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Local, version-pinned initialization experiment. No scan/connect/reset calls. */
public final class ProbeActivity extends Activity {
    private final JSONObject result = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private volatile boolean finished;
    private boolean started;
    private TextView display;
    private synchronized void record(String name, Object value) {
        try {
            stages.put(new JSONObject().put("stage", name).put("value", value));
            result.put("stages", stages);
            persist();
        } catch (Exception e) { android.util.Log.e("RayNeoProbe", "record", e); }
    }
    private synchronized void persist() throws Exception {
        File tmp = new File(getFilesDir(), "result.tmp");
        try(FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(result.toString(2).getBytes("UTF-8")); out.getFD().sync();
        }
        if (!tmp.renameTo(new File(getFilesDir(), "result.json"))) throw new IOException("result rename");
    }
    private static String hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try(InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while((n=in.read(b)) != -1) md.update(b,0,n);
        }
        StringBuilder s = new StringBuilder(); for(byte b:md.digest()) s.append(String.format("%02x",b & 255)); return s.toString();
    }
    private static void require(boolean ok, String why) { if(!ok) throw new IllegalStateException(why); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(1); layout.setPadding(24,48,24,24);
        display = new TextView(this); display.setText("雷鸟独立初始化探针\n只验证初始化、监听注册和自有缓存。\n不扫描、不连接、不解绑眼镜。"); layout.addView(display);
        Button run = new Button(this); run.setText("开始验证"); run.setOnClickListener(v -> runProbe()); layout.addView(run);
        Button stop = new Button(this); stop.setText("结束探针进程"); stop.setOnClickListener(v -> { record("exit_requested",true); finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()); }); layout.addView(stop);
        setContentView(layout);
        if(getIntent().getBooleanExtra("autorun",false)) display.post(this::runProbe);
    }
    private void runProbe() {
        if(started) return; started=true;
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t,e) -> { record("uncaught",android.util.Log.getStackTraceString(e)); if(original!=null) original.uncaughtException(t,e); });
        new Thread(() -> { try { Thread.sleep(30000); } catch(InterruptedException ignored){} if(!finished){record("watchdog_timeout",true); android.os.Process.killProcess(android.os.Process.myPid());} }, "probe-watchdog").start();
        try {
            result.put("package",getPackageName()).put("sdk_int",android.os.Build.VERSION.SDK_INT).put("pid",android.os.Process.myPid()).put("data_dir",getApplicationInfo().dataDir).put("status","running");
            record("begin",true);
            File payload = new File(getFilesDir(),"vendor-payload.jar");
            if(payload.exists() && !payload.delete()) throw new IOException("old payload delete");
            try(InputStream in=getAssets().open("vendor-payload.jar"); FileOutputStream out=new FileOutputStream(payload)) {
                require(payload.setReadOnly(),"payload read-only failed");
                byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); out.getFD().sync();
            }
            require(hash(payload).equals(PayloadInfo.SHA256),"payload integrity failed"); record("payload_sha256",hash(payload));
            ClassLoader cl = new DexClassLoader(payload.getAbsolutePath(), getCodeCacheDir().getAbsolutePath(), null, getClassLoader());
            record("dex_loader_created",true);
            Class<?> entry=cl.loadClass("com.rayneo.rayneo_venus_sdk_plugin.h$b");
            Object initializer=entry.getField("a").get(null);
            Method init=entry.getMethod("c", Context.class, cl.loadClass("G7.H1"));
            record("initialize_enter",true);
            init.invoke(initializer,getApplicationContext(),null);
            record("initialize_returned",true);
            Class<?> coreClass=cl.loadClass("I3.v");
            require(coreClass.getField("i").get(null)==getApplicationContext(),"core context differs");
            require(cl.loadClass("c4.g").getField("b").get(null)==getApplicationContext(),"util context differs");
            record("own_context_identity",true);
            Object companion=coreClass.getField("g").get(null);
            record("singleton_enter",true);
            Object core=companion.getClass().getMethod("a").invoke(companion);
            require(core!=null && coreClass.isInstance(core),"core missing"); record("singleton_created",true);
            Class<?> listenerType=cl.loadClass("T3.g");
            Object listener=java.lang.reflect.Proxy.newProxyInstance(cl,new Class<?>[]{listenerType},(proxy,method,args)-> {
                if(method.getDeclaringClass()==Object.class){ if(method.getName().equals("equals"))return proxy==args[0]; if(method.getName().equals("hashCode"))return System.identityHashCode(proxy); return "ProbeConnectionListener"; }
                record("callback_method",method.getName()); return null;
            });
            Object registry=coreClass.getField("b").get(core);
            List<?> list=(List<?>)registry.getClass().getField("a").get(registry);
            int before=list.size();
            try {
                registry.getClass().getMethod("a",Object.class).invoke(registry,listener);
                require(list.contains(listener)&&list.size()==before+1,"listener insertion failed"); record("listener_registered",true);
            } finally { list.remove(listener); }
            require(!list.contains(listener)&&list.size()==before,"listener removal failed"); record("listener_removed",true);
            SharedPreferences actual=(SharedPreferences)cl.loadClass("I3.l").getMethod("e").invoke(null);
            SharedPreferences own=getSharedPreferences("rayneo_net_bonded_devices",MODE_PRIVATE);
            require(actual==own,"vendor cache not own prefs instance"); record("own_cache_identity",true);
            record("cache_key_count",actual.getAll().size());
            result.put("status","initialization_passed");
            record("scope","Initialization only; no discovery, authentication, real callback delivery or SDK shutdown proven");
        } catch(Throwable e) {
            try { result.put("status","failed"); } catch(Exception ignored){}
            record("failure",android.util.Log.getStackTraceString(e));
        } finally { finished=true; display.setText(result.toString()); }
    }
}
