package dev.xr.rayneo.probe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the explicitly started BLE test session foreground when the screen turns off.
 * No microphone, periodic jobs, cloud sockets or wake locks are owned by this service.
 * The connection owns Bluetooth and stops this service on explicit disconnect or failure.
 */
public final class ConnectionService extends Service {
    static volatile boolean running;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("glasses_connection", "眼镜连接", NotificationManager.IMPORTANCE_LOW));
        startForeground(21, notification(this,"眼镜连接服务","保持蓝牙连接；语音待命状态可在 App 中查看。"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        running = true;
    }
    private static Notification notification(Context context,String title,String text){
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, CloudActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, "glasses_connection")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("雷鸟眼镜连接中")
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    static void recordingState(Context context,String phase){
        if(!running)return;
        boolean active=phase.equals("starting")||phase.equals("recording")||phase.equals("stopping")||phase.equals("saving");
        String title=phase.equals("recording")?"眼镜正在录音":phase.equals("starting")?"正在开启眼镜录音":active?"正在保存眼镜录音":"眼镜已连接";
        try{context.getSystemService(NotificationManager.class).notify(21,notification(context,title,active?"音频仅存手机；点击打开录音控制。":"可在 App 中开启录音或语音待命。"));}catch(SecurityException ignored){}
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running = false; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
}
