package dev.xr.rayneo.probe;
public final class NotificationPolicyCheck {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        NotificationIdentity identities=new NotificationIdentity();
        NotificationIdentity.Entry first=identities.get("app:conversation-a",true);
        check(first.changeType()==1);
        check(identities.get("app:conversation-a",true)==first); // Coalescing before first send is still an add.
        first.submitted=true;check(identities.get("app:conversation-a",true).changeType()==3);
        check(!identities.get("app:conversation-b",true).uid.equals(first.uid));
        identities.remove("app:conversation-a");check(identities.get("app:conversation-a",true).changeType()==1);
        check(identities.get("app:conversation-b",false).changeType()==1);
        identities.clear();check(identities.get("app:conversation-b",true).changeType()==1);
        check(NotificationPolicy.dispatchDelay(0,-1,0)==0); // First message has no artificial delay.
        check(NotificationPolicy.dispatchDelay(1249,1000,0)==1);
        check(NotificationPolicy.dispatchDelay(1250,1000,0)==0);
        check(NotificationPolicy.dispatchDelay(1500,1000,6000)==4500); // Wait for an outstanding send.
        check(NotificationPolicy.dispatchDelay(6000,1000,6000)==0); // Missing callback cannot block forever.
        check(NotificationPolicy.text("你好😀世界",3).equals("你好😀"));
        check(NotificationPolicy.text("甲\u0000乙\n丙\t丁",20).equals("甲乙 丙 丁"));
        check(NotificationPolicy.text(null,120).equals(""));
        for(int mask=0;mask<64;mask++){
            boolean on=(mask&1)!=0,app=(mask&2)!=0,ongoing=(mask&4)!=0,group=(mask&8)!=0,silent=(mask&16)!=0,include=(mask&32)!=0;
            boolean want=on&&app&&!ongoing&&!group&&(!silent||include);
            check(NotificationPolicy.eligible(on,app,ongoing,group,silent,include)==want);
        }
        System.out.println("notification checks passed");
    }
}
