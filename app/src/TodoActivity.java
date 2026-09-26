package dev.xr.rayneo.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.List;

/** Minimal local task lifecycle. Opening this page never sends a device command. */
public final class TodoActivity extends Activity {
    private TodoStore store;
    private TodoGlassesClient glasses;
    private LinearLayout root,list,glassesList;
    private EditText input;
    private TextView feedback,glassesStatus;
    private TodoGlassesClient.Snapshot glassesSnapshot;
    private boolean labPackage(){return getPackageName().equals("dev.xr.rayneo.sdklab");}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(CompanionShell.INK);t.setPadding(0,dp(8),0,dp(8));return t;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new TodoStore(this);if(labPackage())glasses=new TodoGlassesClient(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(CompanionShell.BG);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(8),dp(22),dp(20));scroll.addView(root);setContentView(scroll);
        root.addView(button("返回",this::finish));root.addView(text("我的待办",26));
        root.addView(text(labPackage()?"手机是待办的唯一来源：连上眼镜时和每次修改后，整张列表自动同步到眼镜（照官方做法）；眼镜上勾选完成会同步回手机。首次同步会把眼镜里原有、手机没有的待办收进手机。提醒暂未开放。":"手机待办保存在当前 App。眼镜同步仅在 SDK Lab 验证版开放。",14));
        input=new EditText(this);input.setTextColor(CompanionShell.INK);input.setHintTextColor(CompanionShell.MUTED);input.setHint("输入待办内容");input.setSingleLine(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});root.addView(input);if(state!=null)input.setText(state.getString("draft",""));
        root.addView(button("添加待办",()->{try{TodoStore.Item item=store.create(input.getText().toString());input.setText("");feedback.setText("已保存："+item.title);refreshAfterSave();}catch(Exception e){failure(e);}}));
        feedback=text("",14);root.addView(feedback);list=new LinearLayout(this);list.setOrientation(1);root.addView(list);refresh();
        if(labPackage()){
            root.addView(text("眼镜待办",22));
            glassesStatus=text("点击刷新后，显示当前已连接眼镜里的待办。",14);root.addView(glassesStatus);
            root.addView(button("同步到眼镜",this::syncGlasses));
            root.addView(button("刷新眼镜待办",this::refreshGlasses));
            glassesList=new LinearLayout(this);glassesList.setOrientation(1);root.addView(glassesList);
        }
    }
    private void failure(Exception e){feedback.setText(e instanceof IllegalArgumentException?e.getMessage():"待办读取或保存失败，已有数据保留，请重试");}
    private void refreshAfterSave(){if(!refresh()){feedback.setText("修改已保存，但列表读取失败，请重新打开此页");return;}syncGlasses();}
    /** Every local change pushes the whole list (official model, 09-23 stage 1). Not connected: the
     * next connection syncs anyway (ConnectionService). */
    private void syncGlasses(){
        if(glasses==null||readSession().optString("target_address").isEmpty()||!readSession().optBoolean("connection_ready"))return;
        if(glasses.busy()){glassesStatus.setText("另一项眼镜操作正在进行；连接空闲后请点「同步到眼镜」");return;}
        glasses.fullSync(callback());
    }
    private boolean refresh(){
        try{
            List<TodoStore.Item> items=store.list();list.removeAllViews();
            if(items.isEmpty())list.addView(text("暂无待办",16));
            for(TodoStore.Item item:items){
                list.addView(text((item.completed()?"已完成 · ":"待完成 · ")+item.title,18));
                LinearLayout actions=new LinearLayout(this);list.addView(actions);
                actions.addView(button("修改",()->edit(item)),new LinearLayout.LayoutParams(0,-2,1));
                actions.addView(button(item.completed()?"恢复待办":"标记完成",()->{try{TodoStore.Item saved=store.setCompleted(item.id,!item.completed());feedback.setText(saved.completed()?"已标记完成":"已恢复待办");refreshAfterSave();}catch(Exception e){failure(e);}}),new LinearLayout.LayoutParams(0,-2,1));
                actions.addView(button("删除",()->confirmDelete(item)),new LinearLayout.LayoutParams(0,-2,1));
            }
            return true;
        }catch(Exception e){failure(e);return false;}
    }
    private void edit(TodoStore.Item item){
        EditText title=new EditText(this);title.setText(item.title);title.setSelectAllOnFocus(true);title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("修改待办").setView(title).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{try{store.rename(item.id,title.getText().toString());dialog.dismiss();feedback.setText("修改已保存");refreshAfterSave();}catch(IllegalArgumentException e){title.setError(e.getMessage());}catch(Exception e){failure(e);}}));dialog.show();
    }
    private TodoGlassesClient.Callback callback(){return new TodoGlassesClient.Callback(){
        public void progress(String value){glassesStatus.setText(value);}
        public void complete(TodoGlassesClient.Snapshot value,String message){glassesSnapshot=value;glassesStatus.setText(message);renderGlasses();refresh();}
        public void failed(String message){glassesStatus.setText(message);}
    };}
    private void refreshGlasses(){if(glasses.busy()){glassesStatus.setText("另一项眼镜待办操作正在进行");return;}glasses.refresh(callback());}
    private void confirmDelete(TodoStore.Item item){
        new AlertDialog.Builder(this).setTitle("删除待办").setMessage(item.title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
            try{
                // The glasses identity is kept for the delete command (type 7) the next sync sends.
                String address=readSession().optString("target_address").toUpperCase(java.util.Locale.ROOT);
                android.content.SharedPreferences links=getSharedPreferences("todo-glasses-links",MODE_PRIVATE);
                String eventId=address.isEmpty()?null:links.getString(address+"/"+item.id,null);
                store.delete(item.id);
                if(eventId!=null){
                    android.content.SharedPreferences meta=getSharedPreferences("todo-glasses-meta",MODE_PRIVATE);
                    java.util.Set<String> gone=new java.util.HashSet<>(meta.getStringSet(address+"/deleted",new java.util.HashSet<>()));gone.add(eventId);
                    meta.edit().putStringSet(address+"/deleted",gone).commit();links.edit().remove(address+"/"+item.id).commit();
                }
                feedback.setText("已删除："+item.title);refreshAfterSave();
            }catch(Exception e){failure(e);}
        }).show();
    }
    private void renderGlasses(){
        glassesList.removeAllViews();
        if(glassesSnapshot==null){glassesList.addView(text("尚未读取",16));return;}
        if(glassesSnapshot.items.isEmpty()){glassesList.addView(text("当前眼镜没有待办",16));return;}
        final TodoGlassesClient.Snapshot source=glassesSnapshot;
        for(JSONObject item:source.items)try{
            long id=LabTodoSync.integer(item,"eventID"),status=LabTodoSync.integer(item,"status");String title=item.getString("title");
            glassesList.addView(text((status==1?"已完成 · ":"待完成 · ")+title,18));
            glassesList.addView(button("修改眼镜名称",()->editGlasses(source,Long.toString(id),title)));
        }catch(Exception e){glassesList.addView(text("有一条眼镜待办格式无法显示",14));}
    }
    private void editGlasses(TodoGlassesClient.Snapshot source,String eventId,String oldTitle){
        EditText title=new EditText(this);title.setText(oldTitle);title.setSelectAllOnFocus(true);title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("修改眼镜待办").setView(title).setNegativeButton("取消",null).setPositiveButton("保存并核对",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            String value=title.getText().toString().trim();if(value.isEmpty()){title.setError("请输入待办内容");return;}
            if(glasses.busy()){title.setError("另一项眼镜待办操作正在进行");return;}
            dialog.dismiss();glasses.rename(source,eventId,value,callback());
        }));dialog.show();
    }
    private JSONObject readSession(){
        try{
            File file=new File(getFilesDir(),"result.json");if(!file.isFile()||file.length()>262144)return new JSONObject();
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>262144)return new JSONObject();}}
            return new JSONObject(out.toString("UTF-8"));
        }catch(Exception e){return new JSONObject();}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("draft",input.getText().toString());}
    @Override protected void onDestroy(){if(glasses!=null)glasses.close();if(store!=null)store.close();super.onDestroy();}
}
