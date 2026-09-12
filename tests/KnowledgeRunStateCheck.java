package dev.xr.rayneo.probe;
public final class KnowledgeRunStateCheck {
    private static void check(boolean v){if(!v)throw new AssertionError();}
    private static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("accepted incomplete run");}
    public static void main(String[] args){
        KnowledgeRunState s=new KnowledgeRunState();
        check(!s.completes("old","done"));
        rejects(()->s.event("run",2,"result"));
        check(s.event("run",1,"system"));
        check(!s.event("run",1,"system"));
        check(!s.event("foreign",2,"result"));
        check(s.event("run",2,"progress"));
        rejects(()->s.completes("run","done"));
        rejects(()->s.event("run",4,"result"));
        check(s.event("run",3,"result"));
        check(!s.completes("foreign","done"));
        rejects(()->s.completes("run","error"));
        check(s.completes("run","done"));
        check(!s.event("run",3,"result")); // Replay must not create a second answer.
        rejects(()->s.event("run",4,"result"));
        System.out.println("knowledge run checks passed");
    }
}
