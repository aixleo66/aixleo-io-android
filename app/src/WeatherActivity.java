package dev.xr.rayneo.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;

/** Foreground opt-in; no manual city or region-format substitution. */
public final class WeatherActivity extends Activity {
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshStatus=new Runnable(){public void run(){status.setText(getSharedPreferences("weather",0).getString("last_result","尚未获取当地天气"));handler.postDelayed(this,1000);}};
    private TextView status;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(CompanionShell.INK);t.setPadding(0,dp(10),0,dp(10));return t;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(CompanionShell.BG);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(8),dp(22),dp(20));scroll.addView(root);setContentView(scroll);
        root.addView(button("返回",this::finish));root.addView(text("当地天气",26));
        root.addView(text("使用手机位置，无需填写城市。每次连上眼镜立即推送一次，之后在后台约每15分钟刷新，打开 App 或移动较远时也会补推，锁屏也可更新；也可立即刷新。定位需开启，请允许 App 在后台运行。系统省电可能延后刷新。",15));
        status=text(getSharedPreferences("weather",0).getString("last_display","尚未获取当地天气"),20);root.addView(status);
        root.addView(button("开启 / 刷新自动天气",this::enable));
        root.addView(button("关闭自动天气",()->{getSharedPreferences("weather",0).edit().putBoolean("enabled",false).apply();ConnectionService.disableWeather(this);status.setText("已关闭后续自动刷新；已发送的天气保持不变");}));
        root.addView(button("允许后台定位（始终允许）",this::askBackgroundLocation));
        root.addView(button("电池优化设为不限制",()->{int r=BatteryGuide.ask(this);if(r==BatteryGuide.ALREADY)status.setText("电池优化已是不限制");else if(r==BatteryGuide.UNAVAILABLE)status.setText("本机没有电池优化设置入口；请在「后台运行与权限设置」里手动设为不限制");}));
        root.addView(button("后台运行与权限设置",()->startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))));
        root.addView(text("小米手机请允许自启动、后台运行，并在最近任务中锁定 App。消息通知和日历是各自功能的权限，天气不依赖日历。",14));
        TextView credit=text("天气数据：Open-Meteo（CC BY 4.0）\n当前天气为模型估计，可能与手机系统天气应用有差异。",13);root.addView(credit);
        credit.setOnClickListener(v->startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://open-meteo.com/"))));
        enable();
    }
    private void enable(){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},73);return;}
        getSharedPreferences("weather",0).edit().putBoolean("enabled",true).apply();refresh();
        // Asked once after the approximate location is granted (Android shows its own page for
        // "始终允许"); the button above asks again. Without it, weather after a background restart
        // waits until the App is opened.
        if(!getSharedPreferences("weather",0).getBoolean("bg_location_asked",false)){
            getSharedPreferences("weather",0).edit().putBoolean("bg_location_asked",true).apply();askBackgroundLocation();
        }
    }
    private void askBackgroundLocation(){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){enable();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED){status.setText("后台定位已允许");return;}
        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},74);
    }
    private void refresh(){ConnectionService.weatherFromVisible(this,true);status.setText(getSharedPreferences("weather",0).getString("last_result","正在准备后台天气…"));}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==74){recordBackgroundLocation();return;}if(request==73){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)enable();else status.setText("未获得定位授权，眼镜保留原来的天气。允许大致位置后即可自动获取。");}}
    @Override protected void onStart(){super.onStart();recordBackgroundLocation();handler.post(refreshStatus);}
    /** The real permission, read on every return (the user may grant "始终允许" in system settings,
     * where no result callback comes back). Kept in prefs so it can be checked afterwards. */
    private void recordBackgroundLocation(){
        boolean granted=checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;
        android.content.SharedPreferences w=getSharedPreferences("weather",0);
        boolean before=w.getBoolean("bg_location_granted",false);
        w.edit().putBoolean("bg_location_granted",granted).putLong("bg_location_checked_ms",System.currentTimeMillis()).apply();
        if(granted!=before||w.getBoolean("bg_location_asked",false)&&!granted)
            w.edit().putString("last_result",granted?"后台定位已允许（始终允许）":"未允许后台定位：App 在后台被系统重启后，天气要等打开 App 才恢复").apply();
    }
    @Override protected void onStop(){handler.removeCallbacksAndMessages(null);super.onStop();}
}
