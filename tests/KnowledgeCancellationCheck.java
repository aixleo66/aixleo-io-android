package dev.xr.rayneo.probe;

import java.util.concurrent.*;
import org.json.JSONObject;
import org.java_websocket.client.WebSocketClient;

public final class KnowledgeCancellationCheck {
    public static void main(String[] args)throws Exception{
        JSONObject config=new JSONObject().put("knowledge_url","wss://fixture.invalid/socket")
            .put("knowledge_token","fixture-token");
        CloudClient.Cancellation cancel=new CloudClient.Cancellation();
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> first=executor.submit(()->{
                try{KnowledgeClient.ask(config,"first",cancel);throw new AssertionError("cancelled request returned");}
                catch(InterruptedException expected){}
                catch(Exception error){throw new RuntimeException(error);}
            });
            if(!WebSocketClient.firstConnectEntered.await(2,TimeUnit.SECONDS)){
                if(first.isDone())first.get();
                throw new AssertionError("first request did not enter WSS connect");
            }
            cancel.cancel();
            first.get(2,TimeUnit.SECONDS);

            try{
                KnowledgeClient.ask(config,"second",new CloudClient.Cancellation());
                throw new AssertionError("fixture connection unexpectedly succeeded");
            }catch(CloudClient.Failure expected){
                if(expected.getMessage().contains("已有知识库问题"))throw new AssertionError("cancelled request retained global busy gate");
            }
            if(WebSocketClient.attempts.get()!=2)throw new AssertionError("next request never entered WSS connect");
            System.out.println("knowledge WSS cancellation releases busy and permits immediate next request passed");
        }finally{executor.shutdownNow();}
    }
}
