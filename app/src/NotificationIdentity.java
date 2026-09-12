package dev.xr.rayneo.probe;

import java.util.LinkedHashMap;

/** Android notification keys identify revisions; they do not identify independent chat messages. */
final class NotificationIdentity {
    static final class Entry {
        final String uid; boolean submitted;
        Entry(String uid){this.uid=uid;}
        int changeType(){return submitted?3:1;} // Official added=1, removed=2, updated=3.
    }
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(128,0.75f,true);
    private int next=new java.security.SecureRandom().nextInt(Integer.MAX_VALUE-1)+1;
    Entry get(String key,boolean reuse){
        Entry prior=reuse?entries.get(key):null;
        if(prior!=null)return prior;
        next=next==Integer.MAX_VALUE?1:next+1;
        Entry entry=new Entry(Integer.toString(next));
        entries.put(key,entry);while(entries.size()>128)entries.remove(entries.keySet().iterator().next());
        return entry;
    }
    void remove(String key){entries.remove(key);}
    void clear(){entries.clear();}
}
