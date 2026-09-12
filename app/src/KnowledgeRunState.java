package dev.xr.rayneo.probe;

/** Only an ordered, run-scoped result followed by runEnd(done) is a successful answer. */
final class KnowledgeRunState {
    String runId; long seq; boolean resultReceived;
    boolean event(String id,long next,String type){
        if(id==null||id.isEmpty()||id.length()>128||next<1)throw new IllegalArgumentException("Invalid run event");
        if(runId==null){
            if(next!=1||!type.equals("system"))throw new IllegalArgumentException("Missing run start");
            runId=id;
        }
        if(!runId.equals(id)||next<=seq)return false;
        if(next!=seq+1)throw new IllegalArgumentException("Missing run events");
        if(type.equals("result")){
            if(resultReceived)throw new IllegalArgumentException("Multiple results");
            resultReceived=true;
        }
        seq=next;return true;
    }
    boolean completes(String id,String status){
        if(runId==null||!runId.equals(id))return false;
        if(!status.equals("done")||!resultReceived)throw new IllegalArgumentException("Run did not finish with a result");
        return true;
    }
}
