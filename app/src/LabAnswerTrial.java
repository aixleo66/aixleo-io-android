package dev.xr.rayneo.probe;

/** Wait for a real assistant wake, then send fixed text without requesting audio or cloud work. */
final class LabAnswerTrial {
    static final int NONE=0, QUESTION=1, ANSWER=2, FINISH=3, EXIT=4, FINISHED=-1;
    final String id;
    String phase="prepared",issue="";
    long deadline;
    boolean questionAck,answerAck,finishAck,exitAck,deviceExit,wakeReceived;
    int ignoredPreWakeExits;
    LabAnswerTrial(String id){this.id=id;}
    boolean owns(String value){return id.equals(value);}
    int tick(long now){
        if(phase.equals("prepared")){phase="awaiting_wakeup";deadline=now+45000;return NONE;}
        if(phase.equals("exit_unverified")||phase.equals("ended")||now<deadline)return NONE;
        if(phase.equals("awaiting_wakeup")){issue="wakeup_timeout";phase="ended";return FINISHED;}
        if(phase.equals("exit_pending")){issue="exit_timeout";phase="exit_unverified";return FINISHED;}
        return stop(now,phase.equals("visible_unverified")?"":phase+"_timeout");
    }
    int voice(int type,long now){
        if(phase.equals("awaiting_wakeup")){
            if(type==8){ignoredPreWakeExits++;return NONE;}
            if(type!=1)return NONE;
            wakeReceived=true;phase="question_pending";deadline=now+5000;return QUESTION;
        }
        if(type==8){deviceExit=true;return stop(now,"device_exit");}
        if(type==1||type==11)return stop(now,"new_voice_request");
        return NONE;
    }
    int sent(int action,boolean success,long now){
        String expected=action==QUESTION?"question_pending":action==ANSWER?"answer_pending":action==FINISH?"finish_pending":action==EXIT?"exit_pending":"invalid";
        if(!phase.equals(expected))return NONE;
        if(action==EXIT){exitAck=success;if(!success)issue="exit_send_failed";phase="exit_unverified";return FINISHED;}
        if(!success)return stop(now,expected+"_send_failed");
        deadline=now+5000;
        if(action==QUESTION){questionAck=true;phase="answer_pending";return ANSWER;}
        if(action==ANSWER){answerAck=true;phase="finish_pending";return FINISH;}
        finishAck=true;phase="visible_unverified";deadline=now+12000;return NONE;
    }
    int stop(long now,String reason){
        if(phase.equals("exit_pending")||phase.equals("exit_unverified")||phase.equals("ended"))return NONE;
        if(!reason.isEmpty())issue=reason;
        if(!wakeReceived){phase="ended";return FINISHED;}
        phase="exit_pending";deadline=now+5000;return EXIT;
    }
    boolean transportComplete(){return wakeReceived&&issue.isEmpty()&&questionAck&&answerAck&&finishAck&&exitAck;}
}
