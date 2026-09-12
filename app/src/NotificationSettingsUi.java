package dev.xr.rayneo.probe;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.*;

final class NotificationSettingsUi {
    private final Activity a;private final CompanionShell shell;private final TextView state,apps;
    NotificationSettingsUi(Activity a,CompanionShell s){
        this.a=a;shell=s;
        PhoneNotifications.ensureConnected(a);
        s.title(2,"消息提示","选择要接收的应用和通知类别。");
        state=s.card(2,"");
        toggle("将手机通知转发到眼镜","enabled",false);
        toggle("仅监听检查（暂停眼镜转发）","observe_only",true);
        s.note(2,"排查手机提醒异常时先保持此项开启：只计数通知事件，不读取正文、不向眼镜发送。手机提示确认正常后再关闭此项测试转发。");
        s.action(2,"开启系统通知访问权限",false,()->{try{a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,new ComponentName(a,PhoneNotifications.class).flattenToString()));}catch(Exception e){a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}});
        if("xiaomi".equalsIgnoreCase(Build.MANUFACTURER)){
            s.action(2,"小米后台自启动设置",false,()->{try{a.startActivity(new Intent().setComponent(new ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity")));}catch(Exception e){a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+a.getPackageName())));}});
            s.note(2,"如果权限已开但一直等待服务连接，请允许本 App 后台自启动，再返回这里。");
        }
        s.section(2,"接收哪些通知");apps=s.card(2,"");
        s.action(2,"选择应用",true,this::chooseApps);
        s.action(2,"按应用选择通知类别",false,this::chooseAppChannels);
        s.note(2,"类别列表会在所选应用收到新通知后出现，名称由该应用提供。未关闭的类别默认接收。");
        toggle("包含静默通知","include_silent",false);
        s.section(2,"显示内容");toggle("显示消息正文","body",true);
        s.action(2,"发送一条本机测试通知",false,()->{
            if(!a.getSystemService(NotificationManager.class).areNotificationsEnabled()){
                a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.getPackageName()));
                Toast.makeText(a,"请允许本 App 发送通知，再返回测试",Toast.LENGTH_LONG).show();return;
            }
            PhoneNotifications.postTest(a);Toast.makeText(a,PhoneNotifications.state(),Toast.LENGTH_SHORT).show();refresh();});
        s.note(2,"测试按钮单次验证通知监听与眼镜显示，不需要开启总开关或选择其他应用。");
        s.action(2,"测试同一通知原位更新",false,()->{PhoneNotifications.postUpdateTest(a);Toast.makeText(a,PhoneNotifications.state(),Toast.LENGTH_LONG).show();});
        toggle("合并同一条通知的更新（实验）","coalesce_updates",false);
        s.note(2,"只合并手机系统认定为同一条通知的内容更新；不同会话不强行拼接。先用测试按钮核对眼镜是否支持原位刷新。");
        s.action(2,"设置正文长度",false,()->{
            int[] values={60,120,200,300};String[] labels={"最多 60 字","最多 120 字","最多 200 字","最多 300 字"};int current=PhoneNotifications.prefs(a).getInt("body_limit",120),selected=1;
            for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;
            new AlertDialog.Builder(a).setTitle("正文长度").setSingleChoiceItems(labels,selected,(d,n)->{PhoneNotifications.prefs(a).edit().putInt("body_limit",values[n]).apply();PhoneNotifications.changed();d.dismiss();refresh();}).setNegativeButton("取消",null).show();
        });
        s.note(2,"只转发开启后新到达的通知，不同步历史。关闭正文时仍显示应用和标题。录音、语音问答期间暂缓发送，超过 30 秒的消息略过；持续状态栏通知和分组汇总不转发。正文不保存、不上传云端。");
        refresh();
    }
    private void toggle(String label,String key,boolean fallback){Switch v=new Switch(a);v.setText(label);v.setTextColor(CompanionShell.INK);v.setPadding(0,shell.dp(12),0,shell.dp(12));v.setChecked(PhoneNotifications.prefs(a).getBoolean(key,fallback));v.setOnCheckedChangeListener((b,on)->{PhoneNotifications.prefs(a).edit().putBoolean(key,on).apply();PhoneNotifications.changed();refresh();});shell.pages[2].addView(v);}
    void refresh(){
        boolean granted=a.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(new ComponentName(a,PhoneNotifications.class));
        android.content.SharedPreferences p=PhoneNotifications.prefs(a);
        state.setText(!granted?"尚未开启系统通知访问\n\n点击下方按钮，到系统设置中允许。":PhoneNotifications.connected==null?"系统权限已开启，等待通知服务连接":PhoneNotifications.observeOnly(a)?"仅监听检查 · 不转发\n已收到 "+p.getLong("observed_posts",0)+" 次通知事件":!p.getBoolean("enabled",false)?"通知转发已关闭":"通知转发已开启\n\n"+PhoneNotifications.state());
        if(apps!=null)apps.setText("已选择 "+p.getStringSet("apps",Collections.emptySet()).size()+" 个应用\n\n正文："+(p.getBoolean("body",true)?"最多 "+p.getInt("body_limit",120)+" 字":"不显示"));
    }
    private String label(String pkg){try{return a.getPackageManager().getApplicationLabel(a.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return pkg;}}
    private void chooseApps(){
        Set<String> selected=new HashSet<>(PhoneNotifications.prefs(a).getStringSet("apps",Collections.emptySet()));
        Set<String> all=new HashSet<>(selected);
        for(ResolveInfo r:a.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))if(!r.activityInfo.packageName.equals(a.getPackageName()))all.add(r.activityInfo.packageName);
        List<String> packages=new ArrayList<>(all);packages.sort(Comparator.comparing(this::label));String[] names=new String[packages.size()];boolean[] checked=new boolean[names.length];
        for(int i=0;i<names.length;i++){names[i]=label(packages.get(i));checked[i]=selected.contains(packages.get(i));}
        new AlertDialog.Builder(a).setTitle("选择接收通知的应用").setMultiChoiceItems(names,checked,(d,i,on)->{if(on)selected.add(packages.get(i));else selected.remove(packages.get(i));})
            .setPositiveButton("保存",(d,w)->{PhoneNotifications.prefs(a).edit().putStringSet("apps",selected).apply();PhoneNotifications.changed();refresh();}).setNegativeButton("取消",null).show();
    }
    private void chooseAppChannels(){
        List<String> packages=new ArrayList<>(PhoneNotifications.prefs(a).getStringSet("apps",Collections.emptySet()));packages.sort(Comparator.comparing(this::label));
        if(packages.isEmpty()){Toast.makeText(a,"请先选择应用",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[packages.size()];for(int i=0;i<names.length;i++)names[i]=label(packages.get(i));
        new AlertDialog.Builder(a).setTitle("选择要设置的应用").setItems(names,(d,n)->channels(packages.get(n))).setNegativeButton("取消",null).show();
    }
    private void channels(String pkg){
        try{
            if(PhoneNotifications.connected==null)throw new IllegalStateException();
            org.json.JSONObject known=new org.json.JSONObject(PhoneNotifications.prefs(a).getString("channels_"+pkg,"{}"));List<String> ids=new ArrayList<>();java.util.Iterator<String> it=known.keys();while(it.hasNext())ids.add(it.next());Collections.sort(ids);
            if(ids.isEmpty()){Toast.makeText(a,"尚未收到此应用的通知，收到后可设置类别",Toast.LENGTH_LONG).show();return;}
            Set<String> blocked=new HashSet<>(PhoneNotifications.prefs(a).getStringSet("blocked_channels_"+pkg,Collections.emptySet()));String[] names=new String[ids.size()];boolean[] checks=new boolean[ids.size()];
            for(int i=0;i<ids.size();i++){names[i]=known.optString(ids.get(i));checks[i]=!blocked.contains(ids.get(i));}
            new AlertDialog.Builder(a).setTitle(label(pkg)+" · 已收到的通知类别").setMultiChoiceItems(names,checks,(d,i,on)->{if(on)blocked.remove(ids.get(i));else blocked.add(ids.get(i));})
                .setPositiveButton("保存",(d,w)->{PhoneNotifications.prefs(a).edit().putStringSet("blocked_channels_"+pkg,blocked).apply();PhoneNotifications.changed();refresh();}).setNegativeButton("取消",null).show();
        }catch(Exception e){Toast.makeText(a,"请先开启系统通知访问权限，等待服务连接",Toast.LENGTH_LONG).show();}
    }
}
