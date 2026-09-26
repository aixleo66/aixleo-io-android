package dev.xr.rayneo.probe;
public final class LabAnswerTrialCheck {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        LabAnswerTrial t=new LabAnswerTrial("test");
        check(t.tick(0)==0&&t.phase.equals("awaiting_wakeup"));
        check(t.voice(8,1)==0&&t.ignoredPreWakeExits==1);
        check(t.voice(11,2)==0&&!t.wakeReceived);
        check(t.voice(1,10)==1&&t.wakeReceived);
        check(t.sent(2,true,11)==0&&!t.answerAck);
        check(t.sent(1,true,20)==2);check(t.sent(1,true,21)==0);
        check(t.sent(2,true,30)==3);check(t.sent(3,true,40)==0);
        check(t.tick(12039)==0);check(t.tick(12040)==4);
        check(t.sent(4,true,12050)==-1&&t.transportComplete());
        check(t.tick(99999)==0&&t.stop(99999,"")==0);
        LabAnswerTrial timeout=new LabAnswerTrial("timeout");timeout.tick(0);
        check(timeout.tick(44999)==0&&timeout.tick(45000)==-1);
        check(timeout.issue.equals("wakeup_timeout")&&!timeout.wakeReceived&&!timeout.exitAck);
        check(timeout.tick(90000)==0&&timeout.voice(1,90001)==0);
        LabAnswerTrial waitingStop=new LabAnswerTrial("waiting-stop");waitingStop.tick(0);
        check(waitingStop.stop(1,"session_ending")==-1&&!waitingStop.exitAck);
        LabAnswerTrial cancel=new LabAnswerTrial("cancel");cancel.tick(0);cancel.voice(1,1);
        check(cancel.voice(8,2)==4&&cancel.deviceExit);
        check(cancel.sent(1,true,3)==0&&!cancel.questionAck);
        check(cancel.sent(4,true,4)==-1&&!cancel.transportComplete());
        LabAnswerTrial failed=new LabAnswerTrial("fail");failed.tick(0);failed.voice(1,1);
        check(failed.sent(1,false,5)==4);check(failed.sent(1,true,6)==0&&!failed.questionAck);
        check(failed.sent(4,true,7)==-1&&!failed.transportComplete());
        LabAnswerTrial sendTimeout=new LabAnswerTrial("send-timeout");sendTimeout.tick(0);sendTimeout.voice(1,1);
        check(sendTimeout.tick(5001)==4);check(sendTimeout.tick(10001)==-1&&!sendTimeout.exitAck);
        System.out.println("answer wake lifecycle checks passed");
    }
}
