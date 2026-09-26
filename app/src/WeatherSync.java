package dev.xr.rayneo.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Invoked by the connection's active location foreground service. */
final class WeatherSync implements AutoCloseable {
    interface Feedback{void show(String message);void finished();}
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"phone-weather");t.setDaemon(true);return t;});
    private final Context activity;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Feedback feedback;
    private final PhoneWeather location;
    private final TodoGlassesClient glasses;
    private final CloudClient.Cancellation cancel=new CloudClient.Cancellation();
    private boolean closed,owns,submitted;
    WeatherSync(Context activity,Feedback feedback){this.activity=activity;this.feedback=feedback;location=new PhoneWeather(activity);glasses=new TodoGlassesClient(activity);}
    static boolean enabled(Context activity){return activity.getSharedPreferences("weather",0).getBoolean("enabled",false)&&activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    static boolean due(Context activity){long last=activity.getSharedPreferences("weather",0).getLong("last_sent",0),now=System.currentTimeMillis();return enabled(activity)&&WeatherPolicy.stale(last,now,WeatherPolicy.PERIOD_MS);}
    void start(){
        if(closed||owns)return;
        if(!enabled(activity)){feedback.show("请允许大致位置以自动获取当地天气");feedback.finished();return;}
        if(!ACTIVE.compareAndSet(false,true)){feedback.show("天气正在刷新…");feedback.finished();return;}
        owns=true;feedback.show("正在获取手机大致位置…");
        location.locate(new PhoneWeather.Callback(){
            public void failed(String message){finish(message);}
            public void located(Location position){
                if(closed)return;feedback.show("正在获取当前位置天气…");
                WORKER.execute(()->{
                    try{
                        JSONObject source=PhoneWeather.fetch(position,cancel);
                        WeatherReading reading=WeatherReading.parse(source,System.currentTimeMillis());
                        main.post(()->{
                            if(closed)return;submitted=true;
                            glasses.updateWeather(source,new TodoGlassesClient.Callback(){
                                public void progress(String value){if(!closed)feedback.show(value);}
                                public void complete(TodoGlassesClient.Snapshot value,String message){
                                    activity.getSharedPreferences("weather",0).edit().putLong("last_sent",System.currentTimeMillis()).putString("last_display",reading.description+" · "+reading.temperature+"°C").apply();
                                    finish(reading.description+" · "+reading.temperature+"°C\n"+message);
                                }
                                public void failed(String message){finish(message+"；本次天气更新未确认");}
                            });
                        });
                    }catch(Exception e){main.post(()->finish(e instanceof IllegalArgumentException||e instanceof CloudClient.Failure?e.getMessage():"天气获取失败，眼镜保留原显示，请稍后刷新"));}
                });
            }
        });
    }
    private void finish(String value){if(owns){owns=false;ACTIVE.set(false);if(!closed)feedback.show(value);feedback.finished();}}
    @Override public void close(){closed=true;location.close();cancel.cancel();if(!submitted)finish("");/* A submitted operation retains its callback to release ACTIVE and restore standby. */}
}
