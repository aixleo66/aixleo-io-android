package dev.xr.rayneo.probe;
public final class LabBrightnessTrialCheck {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        check(LabBrightnessTrial.eligible(7,false,0));
        check(!LabBrightnessTrial.eligible(8,false,0));
        check(!LabBrightnessTrial.eligible(7,true,0));
        check(!LabBrightnessTrial.eligible("7",false,0));
        check(!LabBrightnessTrial.eligible(7,false,10001));
        check(!LabBrightnessTrial.eligible(7,false,-1));
        LabBrightnessTrial t=new LabBrightnessTrial("a",false);
        check(t.tick(100)==1);t.status(8);check(!t.targetObserved);
        t.callback(1,true);check(t.tick(1100)==2);t.status(8);check(t.targetObserved);
        check(t.tick(5100)==3);check(!t.passed());t.status(7);check(!t.restored);
        t.callback(3,true);check(t.tick(6100)==4);t.status(7);check(t.passed());check(t.tick(20000)==0);
        LabBrightnessTrial lost=new LabBrightnessTrial("b",false);
        check(lost.tick(0)==1);lost.callback(1,false);check(lost.tick(1)==3);
        check(lost.tick(1001)==4);lost.tick(8001);check(lost.done&&!lost.passed()&&!lost.restored);
        LabBrightnessTrial repair=new LabBrightnessTrial("c",true);
        check(repair.tick(0)==3);repair.callback(3,true);check(repair.tick(1000)==4);
        repair.status(7);check(repair.passed()&&!repair.targetSent);
        LabBrightnessTrial interrupted=new LabBrightnessTrial("d",false);
        interrupted.tick(0);interrupted.interrupt();check(interrupted.done&&!interrupted.passed());
        for(int target:new int[]{1,17}){
            LabBrightnessTrial edge=new LabBrightnessTrial("edge",false,target);
            check(edge.tick(0)==1);edge.callback(1,true);check(edge.tick(1000)==2);
            edge.status(8);check(!edge.targetObserved);edge.status(target);check(edge.targetObserved);
            check(edge.tick(9999)==0);check(edge.tick(10000)==3);edge.callback(3,true);
            check(edge.tick(11000)==4);edge.status(7);check(edge.passed());
            LabBrightnessTrial failed=new LabBrightnessTrial("failed",false,target);
            failed.tick(0);failed.callback(1,false);check(failed.tick(1)==3);
            failed.callback(3,true);failed.tick(1001);failed.status(7);check(failed.restored&&!failed.passed());
        }
        boolean rejected=false;try{new LabBrightnessTrial("bad",false,0);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected);
        System.out.println("brightness lifecycle checks passed");
    }
}
