package dev.xr.rayneo.probe;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;
import java.net.URL;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Approximate system location from a visible page or active location foreground service.
 *
 * <p>09-23 17:09 on device: right after a background reconnect, a fresh network fix gave nothing in
 * 30 s and the fused one was cut by the 45 s limit, while the same request in front took 3 s. A
 * weather grid needs ~1 km, so the order is now: a location the system already has (at most
 * RECENT_NS old) -> fused -> network -> the last fix this App used (at most SAVED_MS old, only
 * when no fix came in time; never when location is off or not permitted). */
final class PhoneWeather implements AutoCloseable {
    static final long RECENT_NS = 10L * 60 * 1_000_000_000L;
    static final long SAVED_MS = 3L * 60 * 60 * 1000;
    interface Callback {void located(Location location);void failed(String reason);}
    private final Context activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CancellationSignal signal;
    private boolean closed,finished;
    PhoneWeather(Context activity){this.activity=activity;}
    void locate(Callback callback){
        try{
            LocationManager manager=activity.getSystemService(LocationManager.class);
            if(manager==null||!manager.isLocationEnabled()){callback.failed("请先开启手机定位服务，再刷新天气");return;}
            if(Build.VERSION.SDK_INT<30){callback.failed("自动天气目前需要 Android 11 或更新版本");return;}
            Location known=recentKnown(manager);
            if(known!=null){finished=true;remember(known);callback.located(known);return;}
            List<String> providers=new ArrayList<>();
            for(String name:new String[]{"fused",LocationManager.NETWORK_PROVIDER})
                if(manager.getAllProviders().contains(name)&&manager.isProviderEnabled(name))providers.add(name);
            if(providers.isEmpty()){callback.failed("手机未提供可用的大致定位服务，请开启网络定位后重试");return;}
            signal=new CancellationSignal();
            main.postDelayed(()->failOrSaved(callback,"定位等待超时，请检查手机定位服务后重试"),45000);
            next(manager,providers,0,callback);
        }catch(SecurityException e){fail(callback,"请允许使用大致位置，以便获取当地天气");}
        catch(Exception e){fail(callback,"手机定位暂时不可用，请稍后刷新");}
    }
    private void next(LocationManager manager,List<String> providers,int index,Callback callback){
        if(closed||finished)return;
        if(index>=providers.size()){failOrSaved(callback,"本次未取得手机位置，请稍后刷新");return;}
        try{manager.getCurrentLocation(providers.get(index),signal,activity.getMainExecutor(),location->{
            if(closed||finished)return;
            long age=location==null?Long.MAX_VALUE:SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos();
            if(location==null||age<0||age>600_000_000_000L){next(manager,providers,index+1,callback);return;}
            finished=true;main.removeCallbacksAndMessages(null);remember(location);callback.located(location);
        });}catch(SecurityException e){fail(callback,"请允许使用大致位置，以便获取当地天气");}
        catch(Exception e){next(manager,providers,index+1,callback);}
    }
    /** Newest location any provider already holds, if younger than RECENT_NS. */
    private Location recentKnown(LocationManager manager){
        Location best=null;long now=SystemClock.elapsedRealtimeNanos();
        for(String name:new String[]{"fused",LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER}){
            try{
                if(!manager.getAllProviders().contains(name))continue;
                Location l=manager.getLastKnownLocation(name);if(l==null)continue;
                long age=now-l.getElapsedRealtimeNanos();
                if(age>=0&&age<=RECENT_NS&&(best==null||l.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos()))best=l;
            }catch(RuntimeException ignored){}
        }
        return best;
    }
    /** Coarse (~1 km, as sent to the weather service) coordinates of the last fix, for failOrSaved. */
    private void remember(Location l){
        activity.getSharedPreferences("weather",0).edit().putString("fix_lat",String.valueOf(Math.round(l.getLatitude()*100)/100.0))
            .putString("fix_lon",String.valueOf(Math.round(l.getLongitude()*100)/100.0))
            .putLong("fix_ms",l.getTime()>0&&l.getTime()<=System.currentTimeMillis()?l.getTime():System.currentTimeMillis()).apply();
    }
    /** No fix in time: reuse the last fix if it is recent enough (the user most likely has not moved
     * far), marked as such; otherwise report the failure. */
    private void failOrSaved(Callback callback,String reason){
        if(closed||finished)return;
        // Location may have been switched off or the permission withdrawn while waiting (review round 13).
        LocationManager manager=activity.getSystemService(LocationManager.class);
        if(manager==null||!manager.isLocationEnabled()
                ||activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            fail(callback,reason);return;
        }
        android.content.SharedPreferences w=activity.getSharedPreferences("weather",0);
        long age=System.currentTimeMillis()-w.getLong("fix_ms",0);
        if(w.contains("fix_lat")&&age>=0&&age<=SAVED_MS){
            try{
                Location saved=new Location("saved_last_fix");
                saved.setLatitude(Double.parseDouble(w.getString("fix_lat","")));saved.setLongitude(Double.parseDouble(w.getString("fix_lon","")));
                saved.setTime(w.getLong("fix_ms",0));
                finished=true;main.removeCallbacksAndMessages(null);if(signal!=null)signal.cancel();callback.located(saved);return;
            }catch(RuntimeException ignored){}
        }
        fail(callback,reason);
    }
    private void fail(Callback callback,String reason){if(closed||finished)return;finished=true;main.removeCallbacksAndMessages(null);if(signal!=null)signal.cancel();callback.failed(reason);}
    static JSONObject fetch(Location location,CloudClient.Cancellation cancel)throws Exception{
        cancel.check();
        // Weather grids do not need a precise home address. Keep only ~1km coordinates.
        double latitude=Math.round(location.getLatitude()*100)/100.0,longitude=Math.round(location.getLongitude()*100)/100.0;
        if(!Double.isFinite(location.getLatitude())||!Double.isFinite(location.getLongitude())||latitude< -90||latitude>90||longitude< -180||longitude>180)throw new CloudClient.Failure("手机位置无效，未获取天气");
        String address="https://api.open-meteo.com/v1/forecast?latitude="+latitude+"&longitude="+longitude
            +"&current=temperature_2m,weather_code,is_day&temperature_unit=celsius&timeformat=unixtime&timezone=GMT&forecast_days=1";
        HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection();
        try{
            cancel.attach(connection);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
            if(connection.getResponseCode()!=200)throw new CloudClient.Failure("天气服务暂时不可用，本次未更新眼镜");
            JSONObject response=new JSONObject(new String(CloudConfig.read(connection.getInputStream(),65536),"UTF-8"));cancel.check();
            return new JSONObject().put("source","Open-Meteo").put("source_url","https://open-meteo.com/")
                .put("kind","current_model_conditions").put("location_source","android_approximate_location")
                .put("location","当前位置").put("latitude",latitude).put("longitude",longitude)
                .put("location_provider",location.getProvider()).put("location_time_ms",location.getTime())
                .put("fetched_at_ms",System.currentTimeMillis()).put("response",response);
        }finally{cancel.detach(connection);connection.disconnect();}
    }
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);if(signal!=null)signal.cancel();}
}
