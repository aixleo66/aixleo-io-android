package dev.xr.rayneo.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.*;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.*;

/** Foreground-only discovery; never connects or changes bonds without selection. */
final class GlassesDevicePicker {
    interface Selection { void select(String address,String name); }
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,String> candidates=new LinkedHashMap<>();
    private final ArrayList<String> addresses=new ArrayList<>(),labels=new ArrayList<>();
    private BluetoothLeScanner scanner;
    private AlertDialog dialog;
    private TextView hint;
    private ArrayAdapter<String> adapter;
    private boolean scanning;
    private String saved;
    GlassesDevicePicker(Activity activity){this.activity=activity;}
    private final ScanCallback callback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->accept(result));}
        @Override public void onBatchScanResults(List<ScanResult> results){handler.post(()->{for(ScanResult r:results)accept(r);});}
        @Override public void onScanFailed(int code){handler.post(()->{if(scanning){stopScan();hint.setText("搜索未能启动，请检查蓝牙后重试。");}});}
    };
    void show(String savedAddress,String savedName,Selection selection){
        close();saved=savedAddress;candidates.clear();addresses.clear();labels.clear();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(1);content.setPadding(40,12,40,12);
        hint=new TextView(activity);hint.setText("让 RayNeo iO 蓝灯闪烁，再选择眼镜。搜索最多 15 秒。");content.addView(hint);
        ListView list=new ListView(activity);adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,labels);
        list.setAdapter(adapter);content.addView(list,new LinearLayout.LayoutParams(-1,480));
        dialog=new AlertDialog.Builder(activity).setTitle("选择眼镜").setView(content).setNegativeButton("取消",null).create();
        dialog.setOnDismissListener(d->stopScan());
        list.setOnItemClickListener((parent,view,position,id)->{
            String address=addresses.get(position),name=candidates.get(address);
            close();selection.select(address,name);
        });
        dialog.show();
        if(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(savedAddress))add(savedAddress,savedName==null||savedName.isEmpty()?"RayNeo iO":savedName,true);
        try{
            android.bluetooth.BluetoothAdapter bluetooth=activity.getSystemService(BluetoothManager.class).getAdapter();
            if(bluetooth==null||!bluetooth.isEnabled()){hint.setText("请先打开手机蓝牙，再重新搜索。");return;}
            scanner=bluetooth.getBluetoothLeScanner();
            if(scanner==null){hint.setText("当前无法搜索蓝牙设备，请稍后重试。");return;}
            scanning=true;
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback);
            handler.postDelayed(()->{if(scanning){stopScan();hint.setText(candidates.isEmpty()?"未发现 RayNeo iO。请确认蓝灯闪烁后重新搜索。":"搜索已结束。“已保存”不代表眼镜当前在线。");}},15000);
        }catch(SecurityException e){stopScan();hint.setText("需要附近设备权限，请授权后重新搜索。");}
        catch(RuntimeException e){stopScan();hint.setText("搜索暂不可用，请稍后重试。");}
    }
    private void accept(ScanResult result){
        if(!scanning||dialog==null||!dialog.isShowing())return;
        try{
            String address=result.getDevice().getAddress();
            String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
            if(name==null)name=result.getDevice().getName();
            String normalized=name==null?"":name.toLowerCase(Locale.ROOT).replace(" ","");
            if(!address.equalsIgnoreCase(saved)&&!((normalized.contains("rayneo")||normalized.contains("雷鸟"))&&normalized.contains("io")))return;
            add(address,name==null?"RayNeo iO":name,false);
        }catch(SecurityException ignored){}
    }
    private void add(String address,String name,boolean remembered){
        name=name.replaceAll("[\\p{Cntrl}]","");if(name.length()>48)name=name.substring(0,48);
        candidates.put(address,name);int index=addresses.indexOf(address);
        String label=name+(remembered?" · 已保存":" · 已发现");
        if(index<0){addresses.add(address);labels.add(label);}else labels.set(index,label);
        adapter.notifyDataSetChanged();
    }
    private void stopScan(){
        handler.removeCallbacksAndMessages(null);
        boolean active=scanning;scanning=false;
        if(active&&scanner!=null)try{scanner.stopScan(callback);}catch(RuntimeException ignored){}
        scanner=null;
    }
    void close(){stopScan();if(dialog!=null){dialog.dismiss();dialog=null;}}
}
