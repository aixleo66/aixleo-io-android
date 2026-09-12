package android.os;
import java.util.*;
/** Deterministic scheduling substitute, not an Android lifecycle emulator. */
public class Handler {
    private static final class Item {final Runnable run;final long due;Item(Runnable r,long d){run=r;due=d;}}
    private final List<Item> items=new ArrayList<>();
    public synchronized boolean post(Runnable r){return postDelayed(r,0);}
    public synchronized boolean postDelayed(Runnable r,long delay){items.add(new Item(r,SystemClock.now+delay));return true;}
    public void runReady(){while(true){Item next=null;synchronized(this){for(Item i:items)if(i.due<=SystemClock.now&&(next==null||i.due<next.due))next=i;if(next!=null)items.remove(next);}if(next==null)return;next.run.run();}}
    public void advance(long ms){SystemClock.now+=ms;runReady();}
    public synchronized List<Runnable> callbacks(){List<Runnable> out=new ArrayList<>();for(Item i:items)out.add(i.run);return out;}
}
