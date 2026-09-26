package dev.xr.rayneo.probe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;

/** App-owned tasks. No vendor identifiers, calendar writes or glasses synchronization. */
final class TodoStore extends SQLiteOpenHelper {
    static final class Item {
        final String id,title,status;
        final long createdAtMs,updatedAtMs;
        Item(Cursor c) {
            id=c.getString(0);title=c.getString(1);status=c.getString(2);
            createdAtMs=c.getLong(3);updatedAtMs=c.getLong(4);
        }
        boolean completed(){return "completed".equals(status);}
    }
    TodoStore(Context context){super(context.getApplicationContext(),"local-todos.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE todos (todo_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 300), status TEXT NOT NULL CHECK(status IN ('pending','completed')), created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        throw new IllegalStateException("待办数据版本暂不支持，原数据已保留");
    }
    static String checkedTitle(String value){
        String title=value==null?"":value.trim();
        if(title.isEmpty()||title.length()>300)throw new IllegalArgumentException("请输入1至300字的待办内容");
        return title;
    }
    private Item get(SQLiteDatabase db,String id){
        if(id==null||id.isEmpty())throw new IllegalArgumentException("请选择一条待办");
        try(Cursor c=db.query("todos",new String[]{"todo_id","title","status","created_at_ms","updated_at_ms"},"todo_id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())throw new IllegalArgumentException("这条待办已不存在，请刷新列表");
            return new Item(c);
        }
    }
    synchronized Item find(String id){return get(getReadableDatabase(),id);}
    synchronized Item create(String value){
        String title=checkedTitle(value),id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        SQLiteDatabase db=getWritableDatabase();ContentValues fields=new ContentValues();
        fields.put("todo_id",id);fields.put("title",title);fields.put("status","pending");
        fields.put("created_at_ms",now);fields.put("updated_at_ms",now);
        db.insertOrThrow("todos",null,fields);
        return get(db,id);
    }
    synchronized Item rename(String id,String title){return update(id,checkedTitle(title),null);}
    synchronized Item createFromVoice(String command,String value){
        String title=checkedTitle(value),id="voice:"+UUID.fromString(command).toString();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            ContentValues fields=new ContentValues();long now=System.currentTimeMillis();
            fields.put("todo_id",id);fields.put("title",title);fields.put("status","pending");
            fields.put("created_at_ms",now);fields.put("updated_at_ms",now);
            db.insertWithOnConflict("todos",null,fields,SQLiteDatabase.CONFLICT_IGNORE);
            Item item=get(db,id);
            if(!item.title.equals(title))throw new IllegalStateException("本轮待办内容冲突，未覆盖");
            db.setTransactionSuccessful();return item;
        }finally{db.endTransaction();}
    }
    /** A glasses todo the phone did not know (09-23 stage 1: the phone becomes the only source, so
     * before its list overwrites the glasses, unknown glasses items are taken in -- nothing is lost).
     * Same event ID, same id every time: adopting twice keeps one row. */
    synchronized Item adopt(long eventId,String value,long createdAtMs,boolean completed){
        if(eventId<=0)throw new IllegalArgumentException("Invalid todo identity");
        String title=checkedTitle(value),id="glasses:"+eventId;long now=System.currentTimeMillis();
        SQLiteDatabase db=getWritableDatabase();ContentValues fields=new ContentValues();
        fields.put("todo_id",id);fields.put("title",title);fields.put("status",completed?"completed":"pending");
        fields.put("created_at_ms",createdAtMs>0?createdAtMs:now);fields.put("updated_at_ms",now);
        db.insertWithOnConflict("todos",null,fields,SQLiteDatabase.CONFLICT_IGNORE);
        return get(db,id);
    }
    /** Removes the row; the caller records the glasses identity for the delete command. */
    synchronized void delete(String id){
        get(getReadableDatabase(),id);
        if(getWritableDatabase().delete("todos","todo_id=?",new String[]{id})!=1)throw new IllegalStateException("待办未删除，请重试");
    }
    VoiceTodoAction.Store voiceActions(){
        return new VoiceTodoAction.Store(){
            private VoiceTodoAction.Entry entry(Item item){return new VoiceTodoAction.Entry(item.id,item.title,item.completed());}
            public VoiceTodoAction.Entry create(String command,String title){return entry(createFromVoice(command,title));}
            public List<VoiceTodoAction.Entry> list(){
                List<VoiceTodoAction.Entry> result=new ArrayList<>();
                for(Item item:TodoStore.this.list())result.add(entry(item));return result;
            }
            public VoiceTodoAction.Entry complete(String id){return entry(setCompleted(id,true));}
        };
    }
    synchronized Item setCompleted(String id,boolean completed){return update(id,null,completed);}
    private Item update(String id,String title,Boolean completed){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            Item before=get(db,id);String checked=title==null?before.title:title;
            String status=completed==null?before.status:completed?"completed":"pending";
            if(!checked.equals(before.title)||!status.equals(before.status)){
                ContentValues fields=new ContentValues();fields.put("title",checked);fields.put("status",status);
                fields.put("updated_at_ms",Math.max(System.currentTimeMillis(),before.updatedAtMs+1));
                if(db.update("todos",fields,"todo_id=?",new String[]{id})!=1)throw new IllegalStateException("待办未更新，请重试");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return get(db,id);
    }
    synchronized List<Item> list(){
        List<Item> items=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("todos",new String[]{"todo_id","title","status","created_at_ms","updated_at_ms"},null,null,null,null,"status DESC, created_at_ms DESC, todo_id")){
            while(c.moveToNext())items.add(new Item(c));
        }
        return items;
    }
}
