package org.java_websocket.client;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

public abstract class WebSocketClient {
    public static final CountDownLatch firstConnectEntered=new CountDownLatch(1);
    private static final CountDownLatch firstConnectClosed=new CountDownLatch(1);
    public static final AtomicInteger attempts=new AtomicInteger();
    public WebSocketClient(URI uri,Draft_6455 draft,Map<String,String> headers,int timeout){}
    public abstract void onOpen(ServerHandshake h);
    public abstract void onMessage(String text);
    public abstract void onClose(int code,String reason,boolean remote);
    public abstract void onError(Exception error);
    public void setConnectionLostTimeout(int seconds){}
    public boolean connectBlocking(long timeout,TimeUnit unit)throws InterruptedException{
        if(attempts.incrementAndGet()==1){
            firstConnectEntered.countDown();
            firstConnectClosed.await(30,TimeUnit.SECONDS);
        }
        return false;
    }
    public void send(String text){}
    public void close(){firstConnectClosed.countDown();}
}
