package dev.xr.rayneo.probe;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.*;
import org.json.*;
import java.util.*;

/** Opt-in forwarding. No notification bodies are persisted or sent to cloud services. */
public final class PhoneNotifications extends NotificationListenerService {
    interface Sink {boolean send(JSONObject payload,String id)throws Exception;}
    static volatile PhoneNotifications connected;
    private static Sink sink;
    private static final ArrayDeque<Item> queue=new ArrayDeque<>();
    private static final LinkedHashMap<String,String> seen=new LinkedHashMap<>();
    private static final NotificationIdentity identities=new NotificationIdentity();
    private static long testGeneration;
    private static long lastSent=-1,inFlightUntil;
    private static String inFlight;
    private static JSONObject inFlightTiming;
    private static NotificationIdentity.Entry inFlightIdentity;
    private static Context appContext;
    private static final Handler dispatchHandler=new Handler(Looper.getMainLooper());
    private static final Runnable dispatch=()->{if(appContext!=null)tick(appContext);};
    private static void schedule(long delay){dispatchHandler.removeCallbacks(dispatch);dispatchHandler.postDelayed(dispatch,delay);}
    private static String testTag;private static long testUntil;
    private static String status="尚未转发消息";
    private static final class Item {
        String key,pkg,channel;long time,postAge;JSONObject payload;boolean test;NotificationIdentity.Entry identity;
        Item(String k,String p,String c,JSONObject j,boolean t,long received,long age){key=k;pkg=p;channel=c;payload=j;test=t;time=received;postAge=age;}
    }
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("phone_notifications",MODE_PRIVATE);}
    static boolean observeOnly(Context c){return prefs(c).getBoolean("observe_only",true);}
    private void count(String key){android.content.SharedPreferences p=prefs(this);p.edit().putLong(key,p.getLong(key,0)+1).apply();}
    static void ensureConnected(Context c){
        ComponentName component=new ComponentName(c,PhoneNotifications.class);
        if(connected==null&&c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component))
            requestRebind(component);
    }
    static boolean allowed(Context c,String pkg,String channel){
        android.content.SharedPreferences p=prefs(c);
        return p.getBoolean("enabled",false)&&p.getStringSet("apps",Collections.emptySet()).contains(pkg)
            &&!p.getStringSet("blocked_channels_"+pkg,Collections.emptySet()).contains(channel);
    }
    static synchronized void attach(Sink s){sink=s;queue.clear();identities.clear();inFlight=null;inFlightTiming=null;inFlightUntil=0;lastSent=-1;dispatchHandler.removeCallbacks(dispatch);}
    static synchronized void detach(Sink s){if(sink==s){sink=null;queue.clear();inFlight=null;inFlightTiming=null;inFlightUntil=0;dispatchHandler.removeCallbacks(dispatch);}}
    static synchronized void changed(){queue.clear();seen.clear();dispatchHandler.removeCallbacks(dispatch);status="设置已更新，等待新通知";}
    static synchronized String state(){return status+(queue.isEmpty()?"":" · 等待发送 "+queue.size()+" 条");}
    static synchronized JSONObject sent(String id,boolean success){
        JSONObject timing=new JSONObject();
        if(id.equals(inFlight)){
            if(!success&&inFlightIdentity!=null)inFlightIdentity.submitted=false;
            timing=inFlightTiming==null?timing:inFlightTiming;
            try{timing.put("sdk_callback_ms",SystemClock.elapsedRealtime()-lastSent).put("success",success);}catch(JSONException ignored){}
            inFlight=null;inFlightTiming=null;inFlightIdentity=null;inFlightUntil=0;
            status=success?"最近一条已发送到眼镜":"最近一条发送失败；未自动重试";
            if(!queue.isEmpty())schedule(NotificationPolicy.dispatchDelay(SystemClock.elapsedRealtime(),lastSent,0));
        }
        return timing;
    }
    static synchronized void postTest(Context c){
        testGeneration++;
        if(connected==null){ensureConnected(c);status="请确认通知访问已开启，等待服务连接后重试";return;}
        if(sink==null){status="请先连接眼镜";return;}
        NotificationManager m=c.getSystemService(NotificationManager.class);
        if(!m.areNotificationsEnabled()){status="请先允许 App 自身发送测试通知";return;}
        m.createNotificationChannel(new NotificationChannel("forward_test","眼镜消息测试",NotificationManager.IMPORTANCE_DEFAULT));
        testTag="forward-test-"+UUID.randomUUID();testUntil=SystemClock.elapsedRealtime()+30000;
        m.notify(testTag,82,new Notification.Builder(c,"forward_test").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("眼镜通知测试")
            .setContentText("这是一条本机生成的测试消息，用于验证手机通知能否显示在眼镜上。").setTimeoutAfter(45000).build());
        status="测试通知已生成，等待系统通知监听";
    }
    static synchronized void postUpdateTest(Context c){
        if(connected==null||sink==null||observeOnly(c)){status="请先连接眼镜并开启正常通知转发";return;}
        Context app=c.getApplicationContext();NotificationManager manager=app.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled()){status="请先允许 App 自身发送测试通知";return;}
        manager.createNotificationChannel(new NotificationChannel("forward_test","眼镜消息测试",NotificationManager.IMPORTANCE_DEFAULT));
        final long generation=++testGeneration;final String tag="forward-update-"+UUID.randomUUID();
        for(int i=0;i<3;i++){final int index=i;dispatchHandler.postDelayed(()->{synchronized(PhoneNotifications.class){
            if(generation!=testGeneration||connected==null||sink==null||observeOnly(app))return;
            testTag=tag;testUntil=SystemClock.elapsedRealtime()+30000;
            String body=index==0?"第一条：你好":index==1?"第一条：你好\n第二条：通知更新":"第一条：你好\n第二条：通知更新\n第三条：更新完成";
            manager.notify(tag,83,new Notification.Builder(app,"forward_test").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("通知更新测试").setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(true).setTimeoutAfter(45000).build());
        }},i*1200L);}
        status="将更新同一条测试通知三次，请核对镜片是否刷新";
    }
    static synchronized void tick(Context c){
        appContext=c.getApplicationContext();
        if(observeOnly(c)){queue.clear();dispatchHandler.removeCallbacks(dispatch);return;}
        long now=SystemClock.elapsedRealtime();
        while(!queue.isEmpty()&&(now-queue.peek().time>30000||(!queue.peek().test&&!allowed(c,queue.peek().pkg,queue.peek().channel)))){queue.remove();status="过期或已关闭的通知已略过";}
        if(queue.isEmpty()||sink==null){dispatchHandler.removeCallbacks(dispatch);return;}
        long delay=NotificationPolicy.dispatchDelay(now,lastSent,inFlightUntil);
        if(delay>0){schedule(delay);return;}
        Item item=queue.peek();
        try{
            String id="forward-"+UUID.randomUUID();
            int changeType=item.identity.changeType();
            item.payload.put("notificationUID",item.identity.uid).put("type",changeType);
            if(sink.send(item.payload,id)){
                item.identity.submitted=true;
                queue.remove();lastSent=now;inFlight=id;inFlightUntil=now+5000;inFlightIdentity=item.identity;
                inFlightTiming=new JSONObject().put("app",item.pkg).put("queue_wait_ms",now-item.time)
                    .put("post_age_at_listener_ms",item.postAge).put("remaining_queue",queue.size())
                    .put("change_type",changeType).put("test",item.test);
                status="正在发送消息";
                if(!queue.isEmpty())schedule(5000);
            }else schedule(250); // Only retry while an actual notification is waiting for the microphone/display owner.
        }
        catch(Exception e){queue.remove();status="消息未发送，等待后续新通知";if(!queue.isEmpty())schedule(250);}
    }
    @Override public void onListenerConnected(){connected=this;count("listener_connections");}
    @Override public void onListenerDisconnected(){connected=null;synchronized(PhoneNotifications.class){queue.clear();dispatchHandler.removeCallbacks(dispatch);status="通知访问连接已断开";}}
    @Override public void onDestroy(){if(connected==this)connected=null;super.onDestroy();}
    @Override public void onNotificationPosted(StatusBarNotification sbn,RankingMap ranks){
        final long received=SystemClock.elapsedRealtime(),postAge=System.currentTimeMillis()-sbn.getPostTime();
        try{
            count("observed_posts");
            // Diagnosis stops before text extraction or Bluetooth forwarding.
            if(observeOnly(this)){synchronized(PhoneNotifications.class){status="仅监听检查：已收到通知事件，未提取正文、未转发";}return;}
            if(!android.os.Process.myUserHandle().equals(sbn.getUser()))return;
            String pkg=sbn.getPackageName();Notification n=sbn.getNotification();
            android.content.SharedPreferences p=prefs(this);
            boolean test;
            synchronized(PhoneNotifications.class){test=pkg.equals(getPackageName())&&testTag!=null&&testTag.equals(sbn.getTag())&&SystemClock.elapsedRealtime()<testUntil;if(test)testTag=null;}
            if(!test&&(pkg.equals(getPackageName())||!p.getStringSet("apps",Collections.emptySet()).contains(pkg)))return;
            Ranking rank=new Ranking();boolean ranked=ranks!=null&&ranks.getRanking(sbn.getKey(),rank);
            NotificationChannel channel=ranked?rank.getChannel():null;
            if(channel!=null&&!test){JSONObject known=new JSONObject(p.getString("channels_"+pkg,"{}"));
                String label=NotificationPolicy.text(channel.getName(),100);
                if((known.has(channel.getId())||known.length()<100)&&!label.equals(known.optString(channel.getId()))){known.put(channel.getId(),label);p.edit().putString("channels_"+pkg,known.toString()).apply();}}
            if(!test&&!allowed(this,pkg,n.getChannelId()))return;
            boolean silent=ranked&&rank.getImportance()<NotificationManager.IMPORTANCE_DEFAULT;
            if(!NotificationPolicy.eligible(true,true,sbn.isOngoing(),(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0,silent,p.getBoolean("include_silent",false)))return;
            String name;
            try{name=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){name=pkg;}
            String title=NotificationPolicy.text(n.extras.getCharSequence(Notification.EXTRA_TITLE),80);
            CharSequence raw=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);if(raw==null||raw.length()==0)raw=n.extras.getCharSequence(Notification.EXTRA_TEXT);
            int limit=Math.max(40,Math.min(300,p.getInt("body_limit",120)));
            String body=p.getBoolean("body",true)?NotificationPolicy.text(raw,limit):"收到一条新通知";
            if(title.isEmpty())title=NotificationPolicy.text(name,80);
            if(body.isEmpty())body="收到一条新通知";
            JSONObject payload=new JSONObject().put("notificationUID",Integer.toString(1+new java.security.SecureRandom().nextInt(Integer.MAX_VALUE-1)))
                .put("appId",pkg).put("appName",NotificationPolicy.text(name,60)).put("title",title).put("subtitle","").put("content",body)
                .put("timestamp",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS",Locale.US).format(new Date(sbn.getPostTime())))
                .put("category",0).put("reply",false).put("type",1);
            String hash=android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest((title+"\n"+body).getBytes("UTF-8")),android.util.Base64.NO_WRAP);
            synchronized(PhoneNotifications.class){
                if(sink==null){status="眼镜未连接，略过本条通知";return;}
                if(hash.equals(seen.get(sbn.getKey())))return;
                seen.put(sbn.getKey(),hash);while(seen.size()>128)seen.remove(seen.keySet().iterator().next());
                queue.removeIf(i->i.key.equals(sbn.getKey()));if(queue.size()>=20)queue.remove();
                Item item=new Item(sbn.getKey(),pkg,n.getChannelId(),payload,test,received,postAge);
                item.identity=identities.get(sbn.getKey(),test||p.getBoolean("coalesce_updates",false));queue.add(item);
            }
            tick(this); // Dispatch on arrival, rather than waiting for the one-second session heartbeat.
        }catch(Exception ignored){synchronized(PhoneNotifications.class){status="有一条通知无法读取，已略过";}}
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn){synchronized(PhoneNotifications.class){queue.removeIf(i->i.key.equals(sbn.getKey()));seen.remove(sbn.getKey());identities.remove(sbn.getKey());}}
}
