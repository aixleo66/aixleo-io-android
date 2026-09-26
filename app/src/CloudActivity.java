package dev.xr.rayneo.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Phone-native provider settings and small, explicit cloud tests. */
public final class CloudActivity extends Activity {
    private JSONObject config;
    private GlassesDevicePicker devicePicker;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, answer, link;
    private EditText question;
    private Switch streaming,followup;
    private AnswerResult currentAnswer=AnswerResult.empty();
    private final CommandWait commandWait=new CommandWait();
    private CloudClient.Cancellation jobCancellation;
    private String renderAnswer(JSONObject value){
        JSONObject body=value.optJSONObject("answer");if(body==null)body=value;
        currentAnswer=new AnswerResult(body.optString("text",value.optString("text")),body.optString("lens_text"));
        StringBuilder text=new StringBuilder();
        String retrieval=body.optString("retrieval_status");
        if("no_match".equals(retrieval))text.append("知识库未命中，本轮回答没有知识库依据。\n\n");
        else if("unknown".equals(retrieval))text.append("知识库依据状态未确认，本轮回答不作为知识库命中结果。\n\n");
        text.append(currentAnswer.text);
        JSONArray sources=body.optJSONArray("sources");
        if(sources!=null){text.append("\n\n来源：");if(sources.length()==0)text.append("本轮没有引用知识库资料");
            for(int i=0;i<sources.length();i++){JSONObject s=sources.optJSONObject(i);if(s!=null)text.append("\n• ").append(s.optString("title")).append("\n  ").append(s.optString("path"));}}
        if(!currentAnswer.lensText.equals(currentAnswer.text))text.append("\n\n眼镜显示短答，完整回答保留在此页。");
        return text.toString();
    }
    private boolean busy;
    private volatile boolean destroyed;
    private PhoneRecording recording;
    private String jobId;
    private java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private CompanionShell shell;
    private NotificationSettingsUi notificationSettings;
    private TextView assistantState, deviceState, deviceSettings;
    private TextView recordingState, nowState, latestRecordAnswer, detailTitle, playbackTime, answerDetail, recordingCount;
    private Button recordStart,recordStop,nowRecord,playPause,moreRecordings;
    private SeekBar playbackProgress;
    private File selectedRecording;
    private boolean playbackReady;
    private boolean recordingsExpanded;
    private LinearLayout recordingList;
    private android.media.MediaPlayer player;
    private File exportRecording;
    private String librarySignature=null;
    private void playRecording(File file){
        stopPlayback();
        try{player=new android.media.MediaPlayer();android.media.MediaPlayer current=player;
            current.setDataSource(file.getAbsolutePath());current.setOnPreparedListener(p->{if(player==p){playbackReady=true;playbackProgress.setMax(p.getDuration());playbackProgress.setEnabled(true);p.start();playPause.setText("暂停");handler.post(playbackTick);status.setText("正在用手机播放录音");}});
            current.setOnCompletionListener(p->{if(player==p){stopPlayback();status.setText("录音播放结束");}});
            current.setOnErrorListener((p,a,b)->{if(player==p){stopPlayback();status.setText("音频播放失败，文件已保留");}return true;});current.prepareAsync();
        }catch(Exception e){stopPlayback();status.setText("无法打开录音文件");}
    }
    private void stopPlayback(){handler.removeCallbacks(playbackTick);playbackReady=false;if(player!=null){player.release();player=null;}if(playPause!=null)playPause.setText("播放录音");if(playbackProgress!=null){playbackProgress.setProgress(0);playbackProgress.setEnabled(false);if(playbackTime!=null)playbackTime.setText("00:00 / "+clockTime(playbackProgress.getMax()));}}
    private String clockTime(long ms){long seconds=Math.max(0,ms/1000);return String.format(Locale.CHINA,"%02d:%02d",seconds/60,seconds%60);}
    private final Runnable playbackTick=new Runnable(){public void run(){if(player==null||!playbackReady)return;try{playbackProgress.setProgress(player.getCurrentPosition());playbackTime.setText(clockTime(player.getCurrentPosition())+" / "+clockTime(player.getDuration()));handler.postDelayed(this,250);}catch(IllegalStateException ignored){stopPlayback();}}};
    private void openRecording(File file,String title,long duration){stopPlayback();selectedRecording=file;detailTitle.setText(title+"\n\n"+clockTime(duration)+" · "+RecordingAudio.label(file)+" · 保存在手机");playbackTime.setText("00:00 / "+clockTime(duration));playbackProgress.setMax((int)Math.min(Integer.MAX_VALUE,duration));shell.show(ShellNavigation.DETAIL);}
    private void refreshRecordings(JSONObject session){
        JSONObject r=isLive(session)?session.optJSONObject("recording"):null;String phase=r==null?"idle":r.optString("phase");
        boolean active=Arrays.asList("connecting","starting","recording","stopping","saving").contains(phase);
        String title=phase.equals("connecting")?"正在准备录音连接":phase.equals("starting")?"等待眼镜确认开始":phase.equals("recording")?"正在录音":phase.equals("stopping")?"已请求停止，等待尾部数据":phase.equals("saving")?RecordingProgress.savingTitle(r.optString("saving_stage")):phase.equals("saved")?"已保存到手机":phase.equals("failed")?"录音未完整保存":phase.equals("cancelled")?"已取消开始录音":"准备录音";
        String detail=r==null?"连接眼镜后点击开始。":phase.equals("failed")?r.optString("error"):phase.equals("saved")?"可在「记录」中播放或导出。":"音频仅保存在这台手机。";
        if(r!=null&&phase.equals("recording")&&r.optLong("confirmed_at_ms")>0)detail="录音计时约 "+Math.max(0,(System.currentTimeMillis()-r.optLong("confirmed_at_ms"))/1000)+" 秒 · "+detail;
        recordingState.setText(title+"\n\n"+detail);recordStart.setEnabled(isLive(session)&&!active&&!busy);recordStop.setEnabled(active&&!phase.equals("saving"));
        shell.heroText(nowState,active?title+"\n\n"+detail:isLive(session)?"眼镜已连接\n\n选择一件想做的事。":"连接你的眼镜\n\n连接后，开始录音或语音提问。");
        nowRecord.setText(active?"查看当前录音":"随身录音");shell.task(title,active);
        File dir=new File(getFilesDir(),"recordings");File[] folders=dir.listFiles(f->f.isDirectory()&&new File(f,"receipt.json").isFile());if(folders==null)folders=new File[0];
        Arrays.sort(folders,(a,b)->Long.compare(b.lastModified(),a.lastModified()));StringBuilder sig=new StringBuilder();
        for(File folder:folders)sig.append(folder.getName()).append(new File(folder,"receipt.json").lastModified());
        if(sig.toString().equals(librarySignature))return;librarySignature=sig.toString();recordingList.removeAllViews();
        recordingCount.setText(folders.length==0?"录音文件":"录音文件 · "+folders.length+" 条");
        moreRecordings.setVisibility(folders.length>3?android.view.View.VISIBLE:android.view.View.GONE);
        moreRecordings.setText(recordingsExpanded?"收起录音列表 ↑":"展开其余 "+(Math.min(folders.length,100)-3)+" 条 ↓");
        if(folders.length==0){TextView t=shell.text("暂无录音文件",14);recordingList.addView(t);}
        int count=0;for(File folder:folders){if(++count>(recordingsExpanded?100:3))break;try{
            File receipt=new File(folder,"receipt.json");if(!receipt.isFile())continue;
            JSONObject meta=new JSONObject(new String(CloudConfig.read(new FileInputStream(receipt),65536),"UTF-8"));
            String label=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(meta.optLong("created_at_ms")));
            File audio=RecordingAudio.playable(folder);if("saved".equals(meta.optString("phase"))&&audio!=null){
                String gap=RecordingAudio.gapNote(meta.optInt("silence_filled_packets"),meta.optLong("duration_ms"));
                TextView t=recordingRow(label,clockTime(meta.optLong("duration_ms"))+gap+" · 查看录音 ›",true);t.setOnClickListener(v->openRecording(audio,label+gap,meta.optLong("duration_ms")));
            }else recordingRow(label,"未完成 · 原始数据保留",false);
        }catch(Exception ignored){recordingRow("暂时无法读取这份录音","原件保留",false);}}
        if(recordingsExpanded&&folders.length>100){TextView t=shell.text("当前显示最近 100 条录音",13);t.setTextColor(CompanionShell.MUTED);recordingList.addView(t);}
    }
    private TextView recordingRow(String title,String subtitle,boolean available){
        TextView t=shell.text("",16);android.text.SpannableString text=new android.text.SpannableString(title+"\n"+subtitle);
        text.setSpan(new android.text.style.RelativeSizeSpan(13f/16f),title.length()+1,text.length(),0);text.setSpan(new android.text.style.ForegroundColorSpan(CompanionShell.MUTED),title.length()+1,text.length(),0);t.setText(text);t.setLineSpacing(shell.dp(5),1);
        t.setPadding(shell.dp(16),shell.dp(14),shell.dp(16),shell.dp(14));t.setBackground(shell.shape(CompanionShell.SURFACE,14));ShellIcon icon=new ShellIcon(4,CompanionShell.GREEN);icon.setBounds(0,0,shell.dp(22),shell.dp(22));t.setCompoundDrawables(icon,null,null,null);t.setCompoundDrawablePadding(shell.dp(14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,shell.dp(10));recordingList.addView(t,p);t.setClickable(available);t.setFocusable(available);return t;
    }
    private void refreshAnswerPreview(){String full=answer.getText().toString();if(!android.text.TextUtils.equals(answerDetail.getText(),full))answerDetail.setText(full);String preview=full.replaceAll("\\s+"," ").trim();if(preview.length()>90)preview=preview.substring(0,90)+"…";if(!android.text.TextUtils.equals(latestRecordAnswer.getText(),preview))latestRecordAnswer.setText(preview);}
    private String displayedCloudId = "";
    private String hiddenCloudJobId = "";
    // Standby can remain healthy while an ASR/provider request fails. Show both states.
    private void refreshGlassesAnswer(JSONObject cloud) {
        if (busy || cloud == null) return;
        String phase=cloud.optString("status"), id=cloud.optString("job_id");
        if(id.isEmpty()||id.equals(hiddenCloudJobId))return;
        if("pending".equals(phase)){
            JSONObject asr=cloud.optJSONObject("asr");
            currentAnswer=AnswerResult.empty();
            answer.setText((asr==null?"":"本轮提问："+asr.optString("text")+"\n\n")+"正在处理本轮问题，回答尚未返回。\n等待期间不持续收音。");
            displayedCloudId=id+":pending";return;
        }
        if(!Arrays.asList("completed","failed","cancelled").contains(phase))return;
        JSONObject delivery=cloud.optJSONObject("delivery");
        String displayId=id+":"+phase+":"+(delivery==null?"":delivery.optString("status"));
        if(displayId.equals(displayedCloudId))return;
        displayedCloudId=displayId;
        JSONObject asr=cloud.optJSONObject("asr");
        String prompt=asr==null?"":"提问："+asr.optString("text")+"\n\n";
        if("completed".equals(phase)&&AnswerPolicy.canDeliver(cloud.optString("text"))){
            answer.setText(prompt+renderAnswer(cloud));
            JSONObject body=cloud.optJSONObject("answer"),action=body==null?null:body.optJSONObject("action");
            String outcome=action==null?"回答已生成":action.optString("status").equals("succeeded")?"待办操作已完成":action.optString("status").equals("rejected")?"待办未修改，请看具体原因":"待办操作未确认成功";
            String sent=delivery==null?"正在准备发送":delivery.optString("status").equals("completed")?"已发送到眼镜":delivery.optString("status").equals("failed")?"眼镜发送失败":"眼镜发送未完成";
            status.setText(outcome+" · "+sent);
        }else{
            currentAnswer=AnswerResult.empty();
            String error=cloud.optString("error");
            if(error.isEmpty())error="cancelled".equals(phase)?"本轮已取消":"本轮未取得可显示的回答";
            answer.setText(prompt+"本轮没有完成回答\n\n"+error);
            status.setText(error);
        }
    }

    private void buildCompanionUi() {
        shell = new CompanionShell(this); status=shell.feedback; link=shell.connection;
        shell.title(7,"此刻","让眼镜陪你，把想法留下来。");
        nowState=shell.hero(7,"正在读取设备状态…");
        nowRecord=shell.action(7,"随身录音",true,()->shell.show(0));
        shell.section(7,"常用");
        shell.row(7,"语音助手","查看待命状态，继续提问",5,()->shell.show(1));
        shell.row(7,"最近记录","录音文件与最近问答",2,()->shell.selectMain(ShellNavigation.RECORDS));
        shell.title(8,"功能","选择一件想做的事。");
        shell.row(8,"随身录音","眼镜收音 · 保存到手机",4,()->shell.show(0));
        shell.row(8,"语音助手","实时识别 · 问答 · 我的知识库",5,()->shell.show(1));
        shell.row(8,"消息提示","选择应用和类别，控制打扰",3,()->shell.show(2));
        shell.row(8,"我的待办","保存在当前 App · 创建与完成",2,()->startActivity(new Intent(this,TodoActivity.class)));
        if(getPackageName().equals("dev.xr.rayneo.sdklab"))shell.row(8,"当地天气","跟随手机位置 · 自动更新到眼镜",0,()->startActivity(new Intent(this,WeatherActivity.class)));
        shell.title(9,"记录","录音留在手机，结果随时回看。");
        recordingCount=shell.text("录音文件",18);recordingCount.setTypeface(null,android.graphics.Typeface.BOLD);recordingCount.setPadding(0,shell.dp(12),0,shell.dp(12));shell.pages[9].addView(recordingCount);
        recordingList=new LinearLayout(this);recordingList.setOrientation(1);shell.pages[9].addView(recordingList);
        moreRecordings=shell.action(9,"展开录音列表",false,()->{recordingsExpanded=!recordingsExpanded;librarySignature=null;try{refreshRecordings(session());}catch(Exception e){status.setText("录音列表暂时无法刷新，原件保留");}});moreRecordings.setVisibility(android.view.View.GONE);
        android.view.View divider=new android.view.View(this);divider.setBackgroundColor(CompanionShell.SOFT);LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,shell.dp(1));dividerParams.setMargins(0,shell.dp(24),0,shell.dp(4));shell.pages[9].addView(divider,dividerParams);
        shell.section(9,"最近问答");latestRecordAnswer=shell.card(9,"尚无问答结果");latestRecordAnswer.setMaxLines(3);latestRecordAnswer.setEllipsize(android.text.TextUtils.TruncateAt.END);latestRecordAnswer.setTextSize(14);latestRecordAnswer.setBackground(shell.shape(0xff182b43,14));
        latestRecordAnswer.setOnClickListener(v->{refreshAnswerPreview();shell.show(ShellNavigation.ANSWER);});latestRecordAnswer.setFocusable(true);
        shell.note(9,"仅预览最近一轮 · 点开查看全文与来源");
        shell.title(12,"问答详情","最近一轮的完整内容与来源。");answerDetail=shell.card(12,"");answerDetail.setTextIsSelectable(true);
        shell.note(12,"当前尚未保存完整问答历史。");shell.action(12,"前往语音助手",false,()->shell.show(1));
        shell.title(3,"设置","设备、服务与偏好。");
        shell.row(3,"眼镜与连接","连接状态、连接与断开",3,()->shell.show(11));
        shell.row(3,"显示与交互","查看当前的显示和退出规则",0,()->shell.show(5));
        shell.row(3,"助手服务","模型、识别与知识库配置",5,()->shell.show(4));
        shell.row(3,"消息偏好","通知权限、应用与正文长度",3,()->shell.show(2));
        shell.section(3,"开发与支持");shell.row(3,"开发者工具","连接诊断与主动测试",3,()->shell.show(6));
        shell.note(3,"AIX IO · 非官方实验项目\n默认深色外观。录音保存在本机；语音识别与问答使用你配置的服务。");

        shell.title(0,"随身录音","用眼镜收音，录音保存在手机。");
        recordingState=shell.card(0,"准备录音\n\n连接眼镜后点击开始。");
        recordStart=shell.action(0,"开始录音",true,()->{stopPlayback();sendSessionCommand("record-start",null);});
        recordStop=shell.action(0,"结束并保存",false,()->sendSessionCommand("record-stop",null));recordStop.setEnabled(false);
        shell.note(0,"连接本 App 后，也可从眼镜的录音菜单发起。结束录音请用手机上的按钮。\n音频只存在这台手机、不上传；录音期间语音待命暂停，保存后会恢复此前开启的待命。");
        root=shell.pages[0];
        field("recording_max_minutes","录音自动结束时长（分钟，5–120）",false);
        shell.action(0,"保存录音设置",false,this::save);
        shell.note(0,"到时间会自动停止并保存。开始录音时会按这个时长检查手机剩余空间，空间不足会拒绝开始。\n每分钟约占 1.7 MB。\n超过 5 分钟的录音此前没有实测过，长时间录音的稳定性尚未验证。");
        shell.action(0,"查看录音文件",false,()->shell.selectMain(ShellNavigation.RECORDS));
        shell.action(0,"查看设备连接",false,()->shell.show(11));
        shell.title(10,"录音详情","音频不上传云端。");detailTitle=shell.card(10,"");
        playbackTime=shell.text("00:00 / 00:00",15);shell.pages[10].addView(playbackTime);
        playbackProgress=new SeekBar(this);playbackProgress.setEnabled(false);shell.pages[10].addView(playbackProgress);
        playbackProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&player!=null&&playbackReady)player.seekTo(p);}});
        playPause=shell.action(10,"播放录音",true,()->{if(player!=null&&playbackReady){if(player.isPlaying()){player.pause();playPause.setText("继续播放");}else{player.start();playPause.setText("暂停");}}else if(selectedRecording!=null)playRecording(selectedRecording);});
        shell.action(10,"停止播放",false,this::stopPlayback);
        shell.action(10,"导出音频",false,()->{if(selectedRecording==null)return;exportRecording=selectedRecording;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(RecordingAudio.mime(selectedRecording)).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"眼镜录音-"+selectedRecording.getParentFile().getName()+RecordingAudio.extension(selectedRecording)),61);});

        shell.title(1,"语音助手","戴着眼镜，开口提问。");
        assistantState=shell.card(1,"正在读取助手状态…");
        shell.action(1,"开启眼镜语音待命",true,()->{if(save())sendSessionCommand("voice-standby",null);});
        shell.action(1,"停止待命 / 取消本轮",false,()->sendSessionCommand("standby-off",null));
        shell.note(1,"连接后自动待命，说“小雷小雷”开始提问。空闲时不收音、不上传、不调用模型；关闭待命会保存选择。系统强制停止 App 后需重新打开连接。");
        shell.section(1,"最近结果"); answer=shell.card(1,"尚无问答结果。眼镜提问完成后，内容会显示在这里。"); answer.setTextIsSelectable(true);
        shell.note(1,"这里显示最近结果；完整历史与上下文将在后续版本接入。");
        shell.section(1,"文字提问"); question=new EditText(this);question.setHint("输入想问的问题");question.setMinLines(2);question.setTextColor(CompanionShell.INK);question.setTextSize(16);shell.pages[1].addView(question);
        shell.action(1,"发送问题",false,()->{if(question.getText().toString().trim().isEmpty()){status.setText("请先输入问题");return;}startJob("text",null);});
        shell.action(1,"将当前结果发送到眼镜",false,this::sendToGlasses);
        shell.section(1,"我的知识库");
        shell.note(1,"连接另一台电脑上的知识库，只读问答。iO 不播音；回答显示在眼镜，完整内容与来源显示在手机。等待期间不持续收音。");
        shell.action(1,"用当前问题查询知识库",false,()->{if(question.getText().toString().trim().isEmpty()){status.setText("请先输入问题");return;}startJob("knowledge",null);});
        shell.action(1,"配置知识库与助手服务",false,()->shell.show(4));
        shell.action(1,"取消手机任务",false,()->{cancelPhoneJob();status.setText("已取消本机任务，不再继续后续请求；已提交的远端任务可能仍在结束");});

        shell.title(11,"眼镜与连接","RayNeo iO");deviceState=shell.card(11,"正在读取连接状态…");
        shell.action(11,"连接眼镜",true,this::connectGlasses);
        shell.action(11,"断开眼镜",false,()->{getSharedPreferences("assistant",MODE_PRIVATE).edit().putBoolean("auto_connect",false).putLong("disconnect_ms",System.currentTimeMillis()).apply();AutoReconnect.stop(this);sendSessionCommand("stop",null);});
        shell.action(11,"搜索并选择眼镜",false,this::chooseGlasses);
        shell.action(11,"眼镜蓝灯闪烁：按配对模式连接",false,()->connectGlasses(false,true));
        shell.action(11,"注册系统设备关联（后台保活）",false,this::registerCompanion);
        shell.note(11,"主动断开后不会自动重连。再次连接会恢复已保存的待命偏好。");

        shell.title(4,"服务配置","Key 加密保存在本机，留空保留已有值。");root=shell.pages[4];
        field("deepseek_key","DeepSeek Key",true);field("deepseek_url","DeepSeek 接口地址",false);field("deepseek_model","对话模型",false);
        field("dashscope_key","阿里云 DashScope Key",true);field("dashscope_url","阿里云文件转写地址",false);field("dashscope_model","文件转写模型",false);
        field("dashscope_text_model","阿里云对话模型",false);
        field("assistant_provider","助手用哪家（deepseek 或 dashscope）",false);
        field("dashscope_stream_url","实时识别 WSS 地址",false);field("dashscope_stream_model","实时识别模型",false);
        field("knowledge_url","知识库 WSS 地址",false);field("knowledge_token","知识库 Token",true);
        shell.note(4,"问题含“知识库”时查询知识库，例如“知识库检索，语音待办如何验收”；其他问题使用 DeepSeek。眼镜的明确待办、录音指令优先按原入口执行。知识库不可用时会报告失败，不自动转给其他服务。临时隧道重启后可在此更新地址。");
        streaming=new Switch(this);streaming.setText("实时识别，边说边显示提问");streaming.setTextColor(CompanionShell.INK);streaming.setChecked(config.optBoolean("streaming_asr"));root.addView(streaming);
        shell.action(4,"保存服务配置",true,this::save);

        shell.title(5,"显示与退出","调整短答退出及免唤醒续问窗口。");root=shell.pages[5];
        shell.card(5,getPackageName().equals("dev.xr.rayneo.sdklab")?
            "经界限验证的紧凑回答：发送完成后按短答时长请求退出。其它回答：不由 App 定时关闭，保留到手动退出或新提问。免唤醒续问时长独立设置；长按表冠可提前退出。\n\n发送完成并不代表镜片已读完；固件息屏仍是独立行为。\n\n眼镜状态与时间同步见「眼镜与连接」。":
            "回答显示：约 15 秒后退出\n\n实时识别：一直没说话约 8 秒后结束收音；说完后停顿一下即结束\n\n眼镜电量可在「眼镜与连接」查看。");
        if(getPackageName().equals("dev.xr.rayneo.sdklab")){
            followup=new Switch(this);followup.setText("允许回答后免唤醒续问");followup.setTextColor(CompanionShell.INK);
            followup.setChecked(config.optBoolean("assistant_followup_enabled",true));root.addView(followup);
            field("assistant_auto_exit_seconds","紧凑回答自动退出（秒，10–120）",false);
            field("assistant_followup_seconds","免唤醒续问窗口（秒，10–120）",false);
            shell.action(5,"保存助手窗口设置",true,this::save);
        }
        shell.note(5,"计时从回答显示完成后开始，长短回答一样；窗口内没说话就退出。翻页不会延长计时。");
        if(getPackageName().equals("dev.xr.rayneo.sdklab")){
            shell.section(5,"眼镜当前设置");
            deviceSettings=shell.card(5,"正在读取眼镜设置…");
            deviceSettings.setTextSize(13);
            // Two buttons, not one. The gate rejects a second command while the first is pending
            // (previous_command_pending), so firing both together always loses one. They are also
            // genuinely different channels: type1 status carries brightness and focusMode, type4
            // settings carries crown/head/wakeup/display -- keep these sources separate.
            // "status", not "query". The dispatcher only knows status (SdkProbeActivity:1342) and
            // throws Unknown session command otherwise -- and that throw is caught by the session
            // tick's catch(Throwable), which fails the whole session, not just the command.
            // session.py's kind whitelist has no "query" either; the CLI action named query maps
            // to kind status. Caught by independent review 2026-09-22.
            buildSettingControls();
            shell.action(5,"刷新设备状态",false,()->readGlasses("status"));
            shell.action(5,"刷新眼镜设置",false,()->readGlasses("lab-settings-query"));
            shell.note(5,"连接眼镜后设置会自动读取，通常不需要手动刷新。\n刷新眼镜设置时如果语音待命开着，眼镜会拒绝，请先在助手页关闭待命。");
        }

        shell.title(6,"开发者工具","主动操作才会执行测试。");root=shell.pages[6];
        field("glasses_address","蓝牙地址（仅调试；通常由选择眼镜自动保存）",false);
        button("保存调试配置",this::save);
        shell.action(6,"测试知识库问答",false,()->startJob("knowledge-test",null));
        button("完成系统配对（首次使用）",()->sendSessionCommand("pair",null));
        button("结束眼镜设置引导（诊断）",()->sendSessionCommand("setup-finish",null));
        button("连接语音数据通道（SPP）",()->sendSessionCommand("spp",null));button("开启眼镜语音唤醒",()->sendSessionCommand("wake",null));
        button("眼镜单轮问答",()->{if(save())sendSessionCommand("voice-native",null);});button("眼镜语音数据诊断",()->sendSessionCommand("voice",null));button("直接检查 8 秒麦克风（不上传）",()->sendSessionCommand("audio",null));
        button("手机麦克风备用诊断（8 秒）",this::startPhoneVoice);button("结束手机诊断并识别",()->{if(recording!=null)recording.stop=true;});button("取消手机诊断与后续请求",this::cancelRecording);
        button("验证阿里云 ASR（上传预置语音）",()->startJob("sample",null));button("选择音频上传转写",()->{if(busy)return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE),31);});
        shell.note(6,"诊断结果显示在助手页；手机录音诊断不产生录音文件。");shell.action(6,"查看最近结果",false,()->shell.show(1));
        shell.section(6,"通知诊断");notificationSettings=new NotificationSettingsUi(this,shell);
        shell.selectMain(ShellNavigation.NOW);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }
    private void registerCompanion() {
        if(busy||!save())return;
        String address=config.optString("glasses_address");
        String outcome=CompanionRegistration.request(this,address);
        // An empty string means the system dialog was asked for; null means nothing needed doing.
        // An association without presence observation never binds the service, so turn it on
        // here too: a pre-existing association may predate this code.
        if(outcome==null)status.setText(CompanionRegistration.observe(this,address)?"系统设备关联已存在，设备在场观察已开启":"系统设备关联已存在，但无法开启设备在场观察");
        else status.setText(outcome.isEmpty()?"请在系统弹窗中确认设备关联":outcome);
    }
    private void connectGlasses() {
        connectGlasses(false);
    }
    private void connectGlasses(boolean automatic) {
        connectGlasses(automatic,false);
    }
    private void connectGlasses(boolean automatic, boolean explicitPairing) {
        if(busy||!save())return;
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED
            ||checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)!=android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_SCAN},51);
            status.setText("请允许附近设备权限以连接眼镜");return;
        }
        try {
            if(isLive(session())){status.setText(session().optBoolean("connection_ready")?"眼镜连接已就绪":"通信已接通，配对尚未确认；请等待配对结果或先断开");return;}
            if(SdkProbeActivity.connectionActive){status.setText("正在连接眼镜，请稍候");return;}
            String address=config.optString("glasses_address");
            if(!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)){if(!automatic)chooseGlasses();else status.setText("请先选择眼镜");return;}
            JSONObject previous=session();
            boolean pairingMode=!automatic&&(explicitPairing||ConnectionAttemptPolicy.usePairingMode(address,previous.optString("target_address"),previous.optString("last_ble_error"),previous.optBoolean("pairing_mode_attempt")));
            boolean repair=ConnectionAttemptPolicy.repairUnbonded(automatic,
                !getSharedPreferences("rayneo_net_bonded_devices",MODE_PRIVATE).getAll().isEmpty(),
                getSystemService(android.bluetooth.BluetoothManager.class).getAdapter().getRemoteDevice(address).getBondState());
            ConnectionService.startSession(this,new Intent().putExtra("target_address",address).putExtra("connect",true).putExtra("pairing_ready",true).putExtra("repair_unbonded",repair).putExtra("pairing_mode_attempt",pairingMode||repair).putExtra("business_probe","session").putExtra("session_seconds",1800).putExtra("companion_ui",true));
            if(!automatic)BatteryGuide.askOnce(this);
        }catch(Exception e){status.setText("无法读取连接状态");}
    }
    private void chooseGlasses(){
        if(busy)return;
        if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED
                ||checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_SCAN},52);return;
        }
        if(devicePicker==null)devicePicker=new GlassesDevicePicker(this);
        devicePicker.show(config.optString("glasses_address"),config.optString("glasses_name"),(address,name)->{
            try{
                if(isLive(session())||SdkProbeActivity.connectionActive){status.setText("已有连接，请先断开后再选择眼镜");return;}
                JSONObject next=new JSONObject(config.toString());next.put("glasses_address",address).put("glasses_name",name);
                CloudConfig.save(this,next);config=next;fields.get("glasses_address").setText(address);
                connectGlasses();
            }catch(Exception e){status.setText("设备选择未能保存，请重试");}
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        if(request==52){boolean allowed=grants.length==2;for(int grant:grants)allowed&=grant==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(allowed)chooseGlasses();else status.setText("需要附近设备权限才能搜索眼镜");return;}
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==51){boolean allowed=grants.length==2;for(int grant:grants)allowed&=grant==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(allowed)connectGlasses();else status.setText("附近设备权限未开启；可再次点击连接授权");}
    }
    private void handleBack(){if(shell!=null&&shell.back())return;moveTaskToBack(true);}
    @Override public void onBackPressed(){handleBack();}
    private void label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setPadding(0,16,0,8); root.addView(view);
    }
    private void button(String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); root.addView(view);
    }
    private void field(String name, String title, boolean secret) {
        label(title, 14); EditText edit = new EditText(this); edit.setSingleLine(true); edit.setTextSize(15);
        edit.setSaveEnabled(false); edit.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        if (secret) edit.setHint(config.optString(name).isEmpty() ? "输入 API Key" : "已配置；留空保留，输入新 Key 可替换");
        else edit.setText(config.optString(name));
        fields.put(name, edit); root.addView(edit);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { config = CloudConfig.load(this); }
        catch (Exception e) { TextView error = new TextView(this); error.setText("配置读取失败；未覆盖已有配置。"); setContentView(error); return; }
        buildCompanionUi();
        if(getSharedPreferences("assistant",MODE_PRIVATE).getBoolean("auto_connect",false))handler.post(()->{
            try{if(!isLive(session()))connectGlasses(true);}catch(Exception ignored){}
        });
        try {
            File prior = new File(getFilesDir(), "cloud-result.json");
            if (prior.isFile()) {
                JSONObject saved = new JSONObject(new String(CloudConfig.read(new FileInputStream(prior), 1048576), "UTF-8"));
                if ("completed".equals(saved.optString("status"))) {
                    String restored = saved.getString("text");
                    if (AnswerPolicy.canDeliver(restored)) {
                        answer.setText(renderAnswer(saved));
                        status.setText("已恢复最近问答，可在「记录」中查看");
                    } else { status.setText("上次回答触发危险建议拦截，请重新提问"); }
                }
            }
        } catch (Exception ignored) { status.setText("配置已加载；上次结果不可用"); }
        handler.post(refreshLink);
    }
    private boolean save() {
        if (busy) { status.setText("正在请求，请完成后再修改配置"); return false; }
        try {
            JSONObject next = new JSONObject(config.toString());
            for (Map.Entry<String, EditText> entry : fields.entrySet()) {
                String value = entry.getValue().getText().toString().trim();
                if(entry.getKey().equals("assistant_auto_exit_seconds")||entry.getKey().equals("assistant_followup_seconds"))
                    next.put(entry.getKey(),Integer.parseInt(value));
                else if (!(entry.getKey().endsWith("_key")||entry.getKey().endsWith("_token")) || !value.isEmpty()) next.put(entry.getKey(), value);
            }
            next.put("streaming_asr", streaming.isChecked());
            if(followup!=null)next.put("assistant_followup_enabled",followup.isChecked());
            StreamingAsr.endpoint(next);
            CloudConfig.save(this, next); config = next;
            for (String name : new String[]{"deepseek_key", "dashscope_key","knowledge_token"}) {
                fields.get(name).setText(""); fields.get(name).setHint(config.optString(name).isEmpty() ? "输入 API Key" : "已配置；留空保留，输入新 Key 可替换");
            }
            status.setText("配置已保存"); return true;
        } catch (Exception e) { status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "配置保存失败；未输出密钥"); return false; }
    }
    private void startJob(String kind, android.net.Uri uri) {
        if (busy || !save()) return;
        final JSONObject settings;
        try { settings = new JSONObject(config.toString()); } catch (Exception e) { return; }
        final String prompt = kind.equals("knowledge-test")?"Rokid 正式版 spokenAnswer 和 displayAnswer 分别如何使用？请根据知识库回答。":question.getText().toString();
        final CloudClient.Cancellation cancel=new CloudClient.Cancellation();jobCancellation=cancel;
        busy = true; clearAnswer(); jobId = UUID.randomUUID().toString(); final String id = jobId;
        answer.setText("等待服务响应…"); status.setText(kind.startsWith("knowledge")||kind.equals("text")&&CloudClient.usesKnowledge(prompt)?"正在查询远端知识库…":kind.equals("text") ? "正在请求 DeepSeek…" : "手机正在上传测试音频到阿里云…");
        worker.submit(() -> {
            JSONObject result;
            try {
                if(kind.startsWith("knowledge"))result=KnowledgeClient.ask(settings,prompt,cancel);
                else if (kind.equals("text")) result = CloudClient.ask(settings, prompt,cancel);
                else {
                    result = CloudPipeline.transcribe(cancel, c -> {
                        c.check();
                        InputStream input = kind.equals("sample") ? new FileInputStream(new File(getFilesDir(), "asr-test.wav")) : getContentResolver().openInputStream(uri);
                        return CloudPipeline.read(input, 5 * 1024 * 1024, c);
                    }, (audio,c) -> {
                        String mime = audio.length >= 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F' ? "audio/wav" : "audio/mpeg";
                        return CloudClient.transcribe(settings, audio, mime,c);
                    });
                }
            } catch (Exception e) {
                result = new JSONObject();
                try { result.put("status", "failed").put("error", e instanceof CloudClient.Failure ? e.getMessage() : e instanceof FileNotFoundException ? "未找到测试音频；可使用选择音频入口" : "网络或响应处理失败，未自动重试"); } catch (Exception ignored) {}
            }
            final JSONObject completed = result;
            handler.post(() -> {
                if (destroyed || !id.equals(jobId)) return;
                busy = false;jobCancellation=null;
                try {
                    completed.put("job_id", id).put("kind", kind).put("pid", android.os.Process.myPid()).put("sampled_at_ms", System.currentTimeMillis());
                    persist("cloud-result.json", completed);
                    if (completed.optString("status").equals("completed")) {
                        answer.setText(renderAnswer(completed));
                        status.setText(completed.optString("provider") + " 成功 · " + completed.optLong("elapsed_ms") + " ms · 尚未发送到眼镜");
                    } else { status.setText(completed.optString("error")); answer.setText("本次请求失败，未发送到眼镜"); }
                } catch (Exception e) { status.setText("结果保存失败"); }
            });
        });
    }
    private void persist(String name, JSONObject value) throws Exception {
        File tmp = new File(getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(value.toString(2).getBytes("UTF-8")); out.getFD().sync(); }
        if (!tmp.renameTo(new File(getFilesDir(), name))) throw new IOException("Rename failed");
    }
    private void clearAnswer(){
        currentAnswer=AnswerResult.empty();
        // Do not redisplay a pre-existing glasses result after cancelling a newer phone job.
        try{JSONObject cloud=session().optJSONObject("glasses_cloud");if(cloud!=null)hiddenCloudJobId=cloud.optString("job_id");}catch(Exception ignored){}
    }
    private void cancelPhoneJob(){
        if(jobCancellation!=null)jobCancellation.cancel();jobCancellation=null;
        if(recording!=null)recording.cancelled=true;recording=null;
        jobId=null;commandWait.invalidate();busy=false;clearAnswer();
        if(answer!=null)answer.setText("本轮已取消，没有可发送的新结果。");
    }
    private void cancelRecording() {
        if (recording != null) { cancelPhoneJob();status.setText("已取消手机诊断，不再发起后续请求；已提交的数据无法撤回"); }
    }
    private void startPhoneVoice() {
        if (busy || !save()) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 41);
            status.setText("请允许手机麦克风权限，再点一次录音开始"); return;
        }
        final JSONObject settings; final String sessionId;
        try {
            JSONObject current = session();
            if (!isLive(current)) throw new CloudClient.Failure("请先连接眼镜，再开始语音问答");
            if (config.optString("dashscope_key").isEmpty())
                throw new CloudClient.Failure("请先保存阿里云 Key");
            settings = new JSONObject(config.toString()); sessionId = current.getString("session_id");
        } catch (Exception e) { status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "无法读取录音配置"); return; }
        final CloudClient.Cancellation cancel=new CloudClient.Cancellation();jobCancellation=cancel;
        busy = true; clearAnswer(); final String id = jobId = UUID.randomUUID().toString();
        final PhoneRecording capture = recording = new PhoneRecording();
        answer.setText("请对着手机提问…"); status.setText("正在启动手机麦克风");
        worker.submit(() -> {
            JSONObject outcome = new JSONObject();
            try {
                CloudPipeline.VoiceResult<JSONObject> result=CloudPipeline.voice(cancel, c -> {
                byte[] wav = capture.capture(this, seconds -> handler.post(() -> {
                    if (!destroyed && id.equals(jobId) && recording == capture && !capture.cancelled)
                        status.setText("手机录音中 · " + seconds + "/8 秒 · 可提前结束或取消");
                }));
                handler.post(() -> { if (!destroyed && id.equals(jobId)) status.setText("录音已停止，阿里云正在识别…"); });
                if (destroyed || capture.cancelled) { Arrays.fill(wav,(byte)0);throw new CloudClient.Failure("录音已取消，未上传"); }
                outcome.put("audio_bytes", wav.length).put("audio_duration_ms", (wav.length - 44) / 32)
                    .put("audio_source", "phone_builtin_mic").put("audio_saved", false);
                return wav;
                }, (wav,c) -> CloudClient.transcribe(settings,wav,"audio/wav",c), (asr,c) -> {
                outcome.put("asr", asr);
                final String transcript = asr.getString("text");
                handler.post(() -> { if (!destroyed && id.equals(jobId)) {
                    answer.setText("识别：" + transcript); status.setText(CloudClient.usesKnowledge(transcript)?"正在查询远端知识库…":"DeepSeek 正在回答…");
                }});
                if (destroyed) throw new CloudClient.Failure("页面已关闭，未继续请求回答");
                c.check();return CloudClient.ask(settings, transcript,c);
                });
                JSONObject asr=result.asr,response=result.answer;
                outcome.put("status", "completed").put("provider", "dashscope → "+response.optString("provider")).put("text", response.getString("text"))
                    .put("llm", response).put("answer",response).put("elapsed_ms", asr.optLong("elapsed_ms") + response.optLong("elapsed_ms"));
            } catch (Exception e) {
                try { outcome.put("status", capture.cancelled ? "cancelled" : "failed")
                    .put("error", e instanceof CloudClient.Failure ? e.getMessage() : "录音或云请求失败，未自动重试"); } catch (Exception ignored) {}
            }
            final JSONObject completed = outcome;
            handler.post(() -> {
                if (destroyed || !id.equals(jobId)) return;
                recording = null; busy = false;jobCancellation=null;
                try {
                    completed.put("job_id", id).put("kind", "phone_voice").put("session_id", sessionId)
                        .put("pid", android.os.Process.myPid()).put("sampled_at_ms", System.currentTimeMillis());
                    persist("cloud-result.json", completed);
                    if (!"completed".equals(completed.optString("status"))) {
                        status.setText(completed.optString("error")); answer.setText(completed.has("asr") ? "识别：" + completed.getJSONObject("asr").getString("text") : "本轮没有生成回答"); return;
                    }
                    answer.setText("识别：" + completed.getJSONObject("asr").getString("text") + "\n\n回答：" + renderAnswer(completed));
                    sendSessionCommand("notify", new JSONObject().put("title", "手机语音问答").put("content", currentAnswer.lensText), sessionId, completed);
                } catch (Exception e) { status.setText("语音结果保存或提交失败；未自动重试"); }
            });
        });
    }
    /** G13: one line about the case, built only from fields that can mean anything right now.
     *
     * <p>Measured 2026-09-19 (capture B5): {@code {inBox, boxBatteryLevel, boxVersion, isCharging,
     * boxColor, boxOpen}}. The trap is that all six arrive whether or not the glasses are in the
     * case. Seen on device 2026-09-22 with the glasses out: {@code boxBatteryLevel:0} and
     * {@code boxOpen:true} -- a level of 0 indistinguishable from a flat case, and a lid state the
     * glasses have no way to observe from outside. Both are placeholders; the user read the lid
     * line as wrong the moment they saw it.
     *
     * <p>So outside the case only {@code inBox} is reported. Distinguish in-case, out-of-case, charging, lid and disconnected states;
     * show whether a value is current, cached or unknown and its timestamp. Unknown is not 0%.
     *
     * <p>{@code boxColor} never appears: an undecoded enum, naming a colour would be a guess. */
    private static String glassesBoxText(JSONObject box){
        if(box==null)return "";
        StringBuilder line=new StringBuilder("\n眼镜盒：");
        Boolean inBox=flag(box,"inBox");
        if(inBox==null)return line.append("状态未知").append(boxAge(box)).toString();
        if(!inBox)return line.append("未放入").append(boxAge(box)).toString();
        line.append("已放入");
        Integer level=number(box,"boxBatteryLevel");
        if(level!=null)line.append(level>0&&level<=100?" · 盒电量 "+level+"%":" · 盒电量未知");
        Boolean charging=flag(box,"isCharging");
        if(charging!=null&&charging)line.append(" · 正在充电");
        // has() would pass an explicit null straight into optBoolean and print 盒盖合上 for it.
        Boolean open=flag(box,"boxOpen");
        if(open!=null)line.append(open?" · 盒盖打开":" · 盒盖合上");
        return line.append(boxAge(box)).toString();
    }
    /** Show the update time next to the value. Only shown once the reading is old
     * enough to matter, so a live line does not carry a distracting "0 秒前". */
    private static String boxAge(JSONObject box){
        if(!(box.opt("at_elapsed_ms") instanceof Number))return "";
        long age=(SystemClock.elapsedRealtime()-((Number)box.opt("at_elapsed_ms")).longValue())/1000L;
        if(age<60)return "";
        return age<3600?"（"+(age/60)+" 分钟前）":"（"+(age/3600)+" 小时前）";
    }
    /** The crown's two action fields share one closed enum, confirmed 2026-09-19 by comparing the
     * official double-tap page against the reverse-engineered values -- all ten matched exactly.
     * Item 6 (全天智记) exists on the double-tap page only, not on screen-off long press. */
    private static String crownAction(int value){
        switch(value){
            case 0: return "语音助手"; case 1: return "返回看板"; case 2: return "勿扰模式";
            case 3: return "录音"; case 4: return "实时字幕"; case 5: return "提词器";
            case 6: return "全天智记开关"; case 7: return "无"; case 8: return "待办";
            case 9: return "实时提示"; default: return "未知("+value+")";
        }
    }
    private static void line(StringBuilder out,String label,String value){
        if(value!=null&&!value.isEmpty())out.append(label).append("：").append(value).append('\n');
    }
    private static String num(JSONObject source,String path){
        Object value=source==null?null:source.opt(path);
        return value instanceof Number?String.valueOf(((Number)value).intValue()):"";
    }
    /** Renders only what the glasses actually reported. Two sources, deliberately kept apart:
     * type1 status carries brightness and focusMode, type4 settings carries the rest;
     * they are different channels and must not be mixed.
     *
     * <p>Undecoded enums are printed as raw numbers, never as invented labels. crownSwitch is the
     * clearest case: it is a 0..3 mode and a device response recorded it as 0/never triggered, so which
     * animation 1/2/3 select is simply unknown. Same for displayConfig height/distance, whose unit
     * has never been established. */
    private static String deviceSettingsText(JSONObject state){
        JSONObject values=state.optJSONObject("reported_settings_values");
        JSONObject status=state.optJSONObject("reported_status");
        if(values==null&&status==null)return "正在读取眼镜设置…";
        // Show current/cached/unknown state and its timestamp. The two sources age separately and
        // they must not be conflated, so the older of the two is what the card can
        // honestly claim -- a settings row from this morning must not look current because the
        // status row refreshed a minute ago.
        String age=staleness(Math.min(
            state.optLong("reported_settings_at_ms",Long.MAX_VALUE),
            state.optLong("reported_status_at_ms",Long.MAX_VALUE)));
        StringBuilder out=new StringBuilder();
        if(status!=null){
            Integer level=newerBrightness(state,status);
            // The official app shows a percentage, not the raw step. Mapping is documented
            //: UI 0% is value 1 and 100% is value 17. Cross-checked against
            // the official screenshot at 38% -- our value 7 gives
            // (7-1)/16 = 37.5%, which rounds to 38%. These measurements agree for the same
            // day, not a paired reading in one session: they corroborate the documented mapping,
            // they are not a measurement we made.
            if(level!=null){
                int step=level.intValue();
                String shown=step>=1&&step<=17?Math.round((step-1)*100f/16f)+"%":step+" / 17";
                Boolean auto=flag(status,"automaticBrightness");
                StringBuilder suffix=new StringBuilder(auto==null?"":auto?" · 自动":" · 手动");
                // Only claim self-adjustment about a level that was actually pushed, and only when
                // the packet says mode 1 -- our own write uses the same cmd name with mode 0
                //, so lux alone would not tell the two apart.
                JSONObject pushed=state.optJSONObject("glasses_brightness");
                if(pushWins(state,status)&&pushed.opt("lux") instanceof Number
                   &&pushed.optInt("mode",-1)==1)
                    suffix.append(" · 眼镜自调，环境光 ")
                          .append(Math.round(((Number)pushed.opt("lux")).doubleValue())).append(" lux");
                line(out,"亮度",shown+suffix);
            }
            JSONObject focus=status.optJSONObject("focusMode");
            if(focus!=null){
                Integer enable=number(focus,"enable");
                StringBuilder value=new StringBuilder(enable==null?"状态未知":enable!=0?"开启":"关闭");
                Integer glassClose=number(focus,"enableGlassClose");
                if(glassClose!=null&&glassClose!=0)value.append(" · 摘下自动退出");
                JSONObject policy=focus.optJSONObject("policy");
                Integer timed=policy==null?null:number(policy,"auto");
                if(timed!=null&&timed!=0)value.append(" · 定时生效");
                line(out,"勿扰",value.toString());
            }
        }
        if(values!=null){
            // Seconds are the units on the official settings page.
            // The sleep choices are 5/10/15/25/40/60/120 seconds; the value belongs to that set.
            String lock=num(values,"generalSettings.autoLockTime");
            // 0 is outside the official option set and was never observed, so whether it means
            // "never sleep" or "not configured" is unknown. Saying either would be inventing a
            // meaning, so the row is simply omitted for that value.
            if(!lock.isEmpty()&&!"0".equals(lock))line(out,"自动息屏",lock+" 秒");
            String degree=num(values,"generalSettings.wakeupConfig.headupDegree");
            String headup=num(values,"generalSettings.wakeupConfig.headupSwitch");
            if(!headup.isEmpty()||!degree.isEmpty())
                line(out,"抬头唤醒",onOff(headup,"开关状态未知")+(degree.isEmpty()?"":" · "+degree+"°"));
            String crownSwitch=num(values,"generalSettings.wakeupConfig.crownSwitch");
            // 0 is documented as off (the observed crown_switch:0 state was off). Which
            // animation 1/2/3 select was never observed, so they get no names -- and the user is
            // not told about our measurement gap, only what can be stated.
            // Only 0 has evidence behind it (observed device response). Calling 1/2/3 "已开启" would be an
            // inference of my own, so the mode number is shown without a verdict.
            line(out,"息屏互动",crownSwitch.isEmpty()?"":"0".equals(crownSwitch)?"关闭"
                :"模式 "+crownSwitch);
            // The official rotation-direction options map 1=自然 / 0=标准,
            // corresponding to the two displayed choices: 标准 / 自然.
            String direction=num(values,"generalSettings.crownConfig.direction");
            line(out,"表冠方向","1".equals(direction)?"自然":"0".equals(direction)?"标准":"");
            String doubleTap=num(values,"generalSettings.crownConfig.double");
            if(!doubleTap.isEmpty())line(out,"表冠双击",crownAction(Integer.parseInt(doubleTap)));
            String longPress=num(values,"generalSettings.crownConfig.longPress");
            if(!longPress.isEmpty())line(out,"息屏长按",crownAction(Integer.parseInt(longPress)));
            // 自然：点头确认摇头取消 / 自定义：相反. Which mode
            // number selects which was never observed, so the pairing is not claimed.
            String head=num(values,"generalSettings.headGestures.enabled");
            String headMode=num(values,"generalSettings.headGestures.mode");
            if(!head.isEmpty())line(out,"头控",onOff(head,"状态未知")
                +(headMode.isEmpty()?"":" · 手势方向 "+headMode));
            String height=num(values,"generalSettings.displayConfig.height");
            String distance=num(values,"generalSettings.displayConfig.distance");
            // The official app shows these as percentages (both read 50% on 2026-09-16 while our
            // values were height 1 / distance 2). One shared reading gives no scale for either, so
            // the raw value stands; the user gets the number, not a note about our gap.
            if(!height.isEmpty()||!distance.isEmpty())
                line(out,"显示位置",(height.isEmpty()?"":"高度 "+height)+(distance.isEmpty()?"":" · 距离 "+distance));
            String mic=num(values,"generalSettings.privacyConfig.mic_switch");
            if(!mic.isEmpty())line(out,"麦克风",onOff(mic,"状态未知"));
        }
        // Reported, cached, but nothing this page knows how to show (the policies admit fields
        // such as led_light/storageTotal that have no row here). Saying "还没读到" would be a
        // different claim, and returning early would also swallow the age prefix.
        if(out.length()==0)return age+"眼镜已回报，但没有本页能显示的设置项。";
        return age+out.toString().trim();
    }
    /** Age suffix for the battery line. Only the push carries a timestamp, so a value that came
     * from the generalStatus cache says so instead of pretending to be live; cached values need an explicit age
     * asks for 实时值/缓存/未知及更新时间, and until now the line claimed none of the three. */
    private static String staleAge(JSONObject power,boolean fromPush){
        if(!fromPush)return "";
        if(!(power.opt("at_elapsed_ms") instanceof Number))return "";
        long age=(SystemClock.elapsedRealtime()-((Number)power.opt("at_elapsed_ms")).longValue())/1000L;
        if(age<120)return "";
        return age<3600?"（"+(age/60)+" 分钟前）":"（"+(age/3600)+" 小时前）";
    }
    /** Wall-clock age prefix for a card, empty while the reading is fresh enough not to matter. */
    private static String staleness(long atMs){
        if(atMs<=0||atMs==Long.MAX_VALUE)return "";
        long age=(System.currentTimeMillis()-atMs)/1000L;
        if(age<60)return "";
        return (age<3600?"更新于 "+(age/60)+" 分钟前":"更新于 "+(age/3600)+" 小时前")+"\n\n";
    }
    /** {@code "1"}/{@code "0"} are the only two values ever observed for these switches; anything
     * else must not be rendered as 关闭. A two-way ternary on {@code "1".equals(x)} turns every
     * unexpected value into a definite "off" -- the same defect as reading a missing boolean as
     * false, flagged across several rows by independent review 2026-09-22. */
    private static String onOff(String raw,String unknown){
        return "1".equals(raw)?"开启":"0".equals(raw)?"关闭":unknown;
    }
    /** {@code JSONObject.has()} is true for an explicit null, and {@code optBoolean} then returns
     * its default -- so guarding with {@code has()} does not stop "missing" from being rendered as
     * a definite state. Judge the value itself. Three rows shipped that bug today
     * (automaticBrightness, focusMode.enable, boxOpen) and the first fix closed only the
     * key-absent half of it. */
    private static Boolean flag(JSONObject source,String key){
        Object value=source==null?null:source.opt(key);
        return value instanceof Boolean?(Boolean)value:null;
    }
    /** Same reasoning as {@link #flag}, for numeric fields. */
    private static Integer number(JSONObject source,String key){
        Object value=source==null?null:source.opt(key);
        return value instanceof Number?((Number)value).intValue():null;
    }
    /** Check the two gate conditions this page can actually hit, and say which one blocked, rather
     * than letting the command fail silently. SessionCommandGate rejects any lab query while
     * standby is on (standby_busy) and any second command while the first is pending
     * (previous_command_pending); both are deliberate and have tests, so the page explains them
     * instead of working around them. */
    private void readGlasses(String kind){
        try{
            JSONObject state=session();
            if(!isLive(state)){status.setText("请先连接眼镜再读取。");return;}
            if(!state.optBoolean("connection_ready")){status.setText("配对尚未完成，设备会拒绝查询。请先在「眼镜与连接」页完成配对。");return;}
            JSONObject standby=state.optJSONObject("standby");
            // status is on the gate's standby allow-list (SessionCommandGate:13); the lab queries
            // are not. Only warn for the ones that will actually be refused.
            if(!"status".equals(kind)&&standby!=null&&standby.optBoolean("enabled")){
                status.setText("语音待命开启时设备会拒绝设置查询。请先在「语音助手」页关闭待命，读取后再开启。");return;
            }
            JSONObject recording=state.optJSONObject("recording");
            if(recording!=null&&!"saved".equals(recording.optString("phase"))&&!recording.optString("phase").isEmpty()
                    &&!"failed".equals(recording.optString("phase"))&&!"cancelled".equals(recording.optString("phase"))){
                status.setText("录音进行中，设备会拒绝查询。请先结束录音。");return;
            }
            JSONObject prior=state.optJSONObject("last_command");
            if(prior!=null&&"pending".equals(prior.optString("status"))){
                status.setText("上一条命令还在执行（"+prior.optString("kind")+"），请稍候重试。");return;
            }
            sendSessionCommand(kind,null);
        }catch(Exception e){status.setText("读取失败："+e.getClass().getSimpleName());}
    }
    private JSONObject session() throws Exception {
        File file = new File(getFilesDir(), "result.json");
        return file.exists() ? new JSONObject(new String(CloudConfig.read(new FileInputStream(file), 262144), "UTF-8")) : new JSONObject();
    }
    private boolean isLive(JSONObject value) {
        return value.optInt("pid") == android.os.Process.myPid() && "sdk_session_ready".equals(value.optString("status")) && value.optBoolean("auth_success_callback");
    }
    private String previousPairingMessage(JSONObject state){
        return state.optBoolean("pairing_mode_attempt")?"配对模式连接仍未成功，需要核对设备状态。":"眼镜正在配对模式，请再点连接以按配对模式接入。";
    }
    private final Runnable refreshLink = new Runnable() { public void run() {
        if (destroyed || link == null) return;
        try { JSONObject state = session(); boolean live=isLive(state);
            boolean ready=live&&state.optBoolean("connection_ready");
            link.setText(ready ? "眼镜已连接 · 查看设备 ›" : live?"通信已接通，等待配对确认 · 查看设备 ›":"眼镜未连接 · 点此连接 ›");
            if(shell!=null){
                if(notificationSettings!=null)notificationSettings.refresh();
                refreshRecordings(state);
                String connectionIssue=state.optString("connection_issue");
                String issueText=connectionIssue.equals("existing_gatt")?"系统仍报告眼镜已有连接，请断开原连接后重试。":connectionIssue.equals("saved_bond_mismatch")?"已保存眼镜与系统配对状态不一致，请先核对配对。":connectionIssue.equals("first_pairing_not_ready")?"首次连接条件未满足，请先核对绑定与配对状态。":connectionIssue.equals("device_pairing_mode")?previousPairingMessage(state):"";
                if(!live&&!issueText.isEmpty())link.setText(issueText+" · 查看连接 ›");
                JSONObject reported=state.optJSONObject("reported_status");
                // G12: prefer the battery_change push over the generalStatus cache. The cache only
                // updates when something asks for it, which is why the percentage sat still while
                // charging. The push arrives on its own; fall back to the cache when none has come.
                JSONObject power=state.optJSONObject("glasses_power");
                Object rawBattery=power!=null&&power.opt("battery") instanceof Number?power.opt("battery")
                    :reported==null?null:reported.opt("battery");
                int battery=rawBattery instanceof Number?((Number)rawBattery).intValue():-1;
                boolean batteryValid=battery>=0&&battery<=100;
                boolean fromPush=power!=null&&power.opt("battery") instanceof Number;
                String batteryText=batteryValid?(live?"电量：":"上次电量：")+battery+"%":"电量：待眼镜回报";
                // isCharging exists only in the generalStatus reply -- the battery_change push
                // carries battery/mode/chargeType/batt_temp and no charging flag (see
                // SdkProbeActivity.putGlassesPower). So the flag cannot be made same-source as the
                // percentage; suppressing it whenever the number came from the push, as the first
                // attempt did, silently turned "sometimes wrong" into "never shown at all".
                //
                // What it can be is fresh. The cache only refreshes when something queries, so a
                // stale one keeps claiming 充电中 long after the glasses left the case. Gate on its
                // own wall-clock timestamp instead, and stay silent rather than assert when it is
                // old. Both problems were found by independent review 2026-09-22, the second one
                // introduced by the fix for the first. chargeType stays an undecoded number in
                // diagnostics and is never turned into words here.
                Boolean charging=flag(reported,"isCharging");
                long statusAt=state.optLong("reported_status_at_ms",0L);
                boolean chargingFresh=statusAt>0&&System.currentTimeMillis()-statusAt<120000L;
                if(batteryValid&&chargingFresh&&charging!=null&&charging)batteryText+=" · 充电中";
                if(batteryValid)batteryText+=staleAge(power,fromPush);
                batteryText+=glassesBoxText(state.optJSONObject("glasses_box"));
                if(live&&!ready)link.setText("等待配对确认 · 查看设备 ›");
                else if(ready&&batteryValid)link.setText("眼镜已连接 · "+battery+"% · 查看设备 ›");
                JSONObject pairing=state.optJSONObject("last_command");
                boolean pairingFailed=pairing!=null&&"pair".equals(pairing.optString("kind"))&&"failed".equals(pairing.optString("status"));
                deviceState.setText("RayNeo iO\n\n"+(live?(ready?"业务连接与系统配对已就绪":pairingFailed?
                    "配对未完成，请断开后重试；通信和电量回报不代表配对完成。":"通信已接通，等待配对确认。\n如手机出现系统配对提示，请确认。"):
                    issueText.isEmpty()?"当前没有有效业务连接":issueText)+"\n"+batteryText);
                if(deviceSettings!=null)deviceSettings.setText(deviceSettingsText(state));
                showSettingOutcome(state.optJSONObject("last_command"));
                JSONObject standby=state.optJSONObject("standby"), voice=state.optJSONObject("voice_test");
                String phase=voice==null?"":voice.optString("phase");
                String title=!live?"等待连接眼镜":!ready?"等待配对完成":standby!=null&&standby.optBoolean("ready")?"已待命 · 说“小雷小雷”":"待命未开启";
                if(live&&standby!=null&&standby.optBoolean("enabled")){
                    if("recording".equals(phase))title="正在听你说";
                    else if("answering".equals(phase)){
                        long started=voice.optLong("query_started_elapsed_ms"),now=android.os.SystemClock.elapsedRealtime();
                        title=("knowledge".equals(voice.optString("answer_provider"))?"正在查询知识库":"正在生成回答")+(started>0&&started<=now?" · 已等待 "+((now-started)/1000)+" 秒":"")+"\n已结束收音，等待结果";
                    }
                    else if("syncing_todo".equals(phase))title="手机待办已处理 · 正在核对眼镜同步";
                    else if("answering".equals(phase)||"transcribing".equals(phase)||"finalizing_transcript".equals(phase))title="正在处理你的问题";
                    else if("sending_answer".equals(phase))title="正在向眼镜发送回答";
                    else if(state.optJSONObject("lab_native_reading")!=null&&"awaiting_user_exit".equals(state.getJSONObject("lab_native_reading").optString("state")))title="正在眼镜阅读 · 退出后可再次唤醒";
                }
                if(live&&"handed_to_recording".equals(phase)){
                    JSONObject rec=state.optJSONObject("recording");String rp=rec==null?"":rec.optString("phase");
                    title="recording".equals(rp)?"已开始普通录音 · 请用手机或表冠停止":"saved".equals(rp)?"录音已保存 · 正在恢复助手":"failed".equals(rp)?"录音未完整保存 · 请查看录音记录":"正在处理普通录音 · 请查看录音状态";
                }
                assistantState.setText(title);
                JSONObject cloud=state.optJSONObject("glasses_cloud");
                if(live)refreshGlassesAnswer(cloud);
                refreshAnswerPreview();
            }
        }
        catch (Exception e) { link.setText("连接状态暂不可用"); }
        handler.postDelayed(this, 2000);
    }};
    private void sendToGlasses() {
        if (busy || !currentAnswer.available()) return;
        try {
            String display=currentAnswer.lensText;
            if (display.codePointCount(0, display.length()) > 500) throw new CloudClient.Failure("结果超过 500 字，请先生成短摘要");
            sendSessionCommand("notify", new JSONObject().put("title", "助手回答").put("content", display));
        } catch (Exception e) { status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "无法提交眼镜通知"); }
    }
    /** The editable half of the settings page.
     *
     * <p>Controls send on release or on selection rather than on every increment: a seek bar
     * dragged across its range would otherwise put a write on the wire per pixel. Each write is
     * followed by a device-side read-back, so nothing here assumes the value took effect -- the
     * card above is refreshed from whatever the glasses report next.
     *
     * <p>Current values are read once when the page is built. They are not rebound afterwards, so
     * a control shows what was there when you opened the page; the card is the live view. */
    private void buildSettingControls() {
        JSONObject values, status, state;
        try { state = session(); values = state.optJSONObject("reported_settings_values");
              status = state.optJSONObject("reported_status"); }
        catch (Exception unreadable) { values = null; status = null; state = null; }

        shell.section(5, "调整眼镜设置");
        root = shell.pages[5];

        // Brightness: 1..17 on the wire, shown as the official 0-100%.
        // The glasses also move this themselves, so the slider starts from whichever source is
        // newer -- otherwise it opens on a value the firmware has already left behind.
        // final so that a later "just default it" cannot be added here without the build failing:
        // a source-text test can always be slipped past by writing the default a different way.
        // Unlike the groups below, the slider stays even when the level is unknown. Brightness is
        // written as a single field, so a starting position cannot overwrite a setting the user
        // never touched -- the reason those groups go read-only does not apply here. Making it
        // disappear removed the control outright, and missingGroup's shared hint points at
        // 「刷新眼镜设置」, which is the type4 read and carries no brightness at all.
        // Both caught by independent review 2026-09-22.
        final Integer brightNow = newerBrightness(state, status);
        final int step = brightNow == null ? 0 : Math.max(1, Math.min(17, brightNow.intValue()));
        label("亮度", 14);
        final TextView brightnessValue = shell.text(
            step == 0 ? "当前值未读到，可直接拖动设置" : Math.round((step - 1) * 100f / 16f) + "%", 14);
        root.addView(brightnessValue);
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(16);
        // Mid-scale is a starting position, not a claim about the current level; the label says so
        // until the user moves it.
        brightness.setProgress(step == 0 ? 8 : step - 1);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                brightnessValue.setText(Math.round(progress * 100f / 16f) + "%");
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) {
                try { writeGlassesSetting("brightness", new JSONObject().put("value", bar.getProgress() + 1)); }
                catch (Exception e) { status("亮度未发出"); }
            }
        });
        root.addView(brightness);

        // The seven options the official page offers; anything else is refused by the device.
        final int[] sleepOptions = {5, 10, 15, 25, 40, 60, 120};
        String[] sleepLabels = new String[sleepOptions.length];
        for (int i = 0; i < sleepOptions.length; i++) sleepLabels[i] = sleepOptions[i] + " 秒";
        Integer sleepNow = settingInt(values, "generalSettings.autoLockTime");
        if (sleepNow == null) missingGroup("自动息屏");
        else {
            int sleepIndex = 1;
            for (int i = 0; i < sleepOptions.length; i++) if (sleepOptions[i] == sleepNow.intValue()) sleepIndex = i;
            addChoice("自动息屏", sleepLabels, sleepIndex, new Chosen() {
                public void at(int index) {
                    try { writeGlassesSetting("auto_lock", new JSONObject().put("value", sleepOptions[index])); }
                    catch (Exception e) { status("自动息屏未发出"); }
                }
            });
        }

        // headup switch, degree and crown mode travel in one packet: changing any one of them
        // rewrites all three. So the group is only editable when the glasses have reported all
        // three -- otherwise a default filled in here would overwrite a setting nobody touched.
        Integer wakeSwitch = settingInt(values, "generalSettings.wakeupConfig.headupSwitch");
        Integer wakeDegree = settingInt(values, "generalSettings.wakeupConfig.headupDegree");
        Integer wakeCrown = settingInt(values, "generalSettings.wakeupConfig.crownSwitch");
        if (!allPresent(wakeSwitch, wakeDegree, wakeCrown)) missingGroup("抬头唤醒与息屏互动");
        else {
            final int[] wake = {wakeSwitch, wakeDegree, wakeCrown};
            addSwitch("抬头唤醒", wake[0] == 1, new Toggled() {
                public void to(boolean on) { wake[0] = on ? 1 : 0; sendWake(wake); }
            });
            label("抬头角度", 14);
            final TextView degreeValue = shell.text(wake[1] + "°", 14);
            root.addView(degreeValue);
            SeekBar degree = new SeekBar(this);
            degree.setMax(90);
            degree.setProgress(Math.max(0, Math.min(90, wake[1])));
            degree.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { degreeValue.setText(progress + "°"); }
                public void onStartTrackingTouch(SeekBar bar) { }
                public void onStopTrackingTouch(SeekBar bar) { wake[1] = bar.getProgress(); sendWake(wake); }
            });
            root.addView(degree);
            // 0 is documented as off (observed device response); 1..3 select animations nobody has observed, so
            // they are offered by number rather than given invented names.
            addChoice("息屏互动", new String[]{"关闭", "模式 1", "模式 2", "模式 3"}, Math.max(0, Math.min(3, wake[2])),
                new Chosen() { public void at(int index) { wake[2] = index; sendWake(wake); } });
        }

        // direction rides in value, the two actions in a JSON string -- again one packet.
        Integer crownDirection = settingInt(values, "generalSettings.crownConfig.direction");
        Integer crownDouble = settingInt(values, "generalSettings.crownConfig.double");
        Integer crownLong = settingInt(values, "generalSettings.crownConfig.longPress");
        if (!allPresent(crownDirection, crownDouble, crownLong)) missingGroup("表冠");
        else {
            final int[] crown = {crownDirection, crownDouble, crownLong};
            addChoice("表冠方向", new String[]{"标准", "自然"}, crown[0] == 1 ? 1 : 0,
                new Chosen() { public void at(int index) { crown[0] = index; sendCrown(crown); } });
            addChoice("表冠双击", ACTION_LABELS, actionIndex(crown[1]),
                new Chosen() { public void at(int index) { crown[1] = ACTION_VALUES[index]; sendCrown(crown); } });
            // 全天智记 (6) exists on the double-tap page only, so the long-press list is one shorter.
            String[] longLabels = new String[ACTION_LABELS.length - 1];
            final int[] longValues = new int[ACTION_VALUES.length - 1];
            for (int i = 0, j = 0; i < ACTION_VALUES.length; i++) {
                if (ACTION_VALUES[i] == 6) continue;
                longLabels[j] = ACTION_LABELS[i]; longValues[j] = ACTION_VALUES[i]; j++;
            }
            int longIndex = 0;
            for (int i = 0; i < longValues.length; i++) if (longValues[i] == crown[2]) longIndex = i;
            addChoice("息屏长按", longLabels, longIndex,
                new Chosen() { public void at(int index) { crown[2] = longValues[index]; sendCrown(crown); } });
        }

        Integer headEnabled = settingInt(values, "generalSettings.headGestures.enabled");
        Integer headMode = settingInt(values, "generalSettings.headGestures.mode");
        if (!allPresent(headEnabled, headMode)) missingGroup("头控");
        else {
            final int[] head = {headEnabled, headMode};
            addSwitch("头控手势", head[0] == 1, new Toggled() {
                public void to(boolean on) { head[0] = on ? 1 : 0; sendHead(head); }
            });
            // Which number is 点头确认 and which is the reverse was never observed
            // (the observed settings establish only that the two directions can be swapped), so the choice
            // is offered as an unnamed pair rather than with labels we would be inventing.
            addChoice("头控方向", new String[]{"方向 0", "方向 1"}, head[1] == 1 ? 1 : 0,
                new Chosen() { public void at(int index) { head[1] = index; sendHead(head); } });
        }

        shell.note(5, "改动会立即发送到眼镜，随后自动读回确认，上方卡片显示的是眼镜回报的值。\n显示位置（高度/距离）暂不可改：写入指令尚未取得。");
    }

    private static final String[] ACTION_LABELS = {"语音助手", "返回看板", "勿扰模式", "录音", "实时字幕", "提词器", "全天智记开关", "无", "待办", "实时提示"};
    private static final int[] ACTION_VALUES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    private static int actionIndex(int value) {
        for (int i = 0; i < ACTION_VALUES.length; i++) if (ACTION_VALUES[i] == value) return i;
        return 0;
    }

    private void sendWake(int[] wake) {
        try { writeGlassesSetting("wakeup", new JSONObject().put("headup_switch", wake[0])
            .put("headup_degree", wake[1]).put("crown_switch", wake[2])); }
        catch (Exception e) { status("抬头唤醒未发出"); }
    }
    private void sendCrown(int[] crown) {
        try { writeGlassesSetting("crown", new JSONObject().put("direction", crown[0])
            .put("double", crown[1]).put("longPress", crown[2])); }
        catch (Exception e) { status("表冠设置未发出"); }
    }
    private void sendHead(int[] head) {
        try { writeGlassesSetting("head", new JSONObject().put("enabled", head[0]).put("mode", head[1])); }
        catch (Exception e) { status("头控设置未发出"); }
    }
    private void status(String text) { if (status != null) status.setText(text); }
    /** Surfaces a refusal from the device. Ranges are enforced there, not here, so this is the only
     * place the user learns why a setting was rejected -- before this the status bar showed the
     * generic "本轮测试未通过" and last_command.reason was never read. */
    private void showSettingOutcome(JSONObject last) {
        if (last == null || !"apply-setting".equals(last.optString("kind"))) return;
        String state = last.optString("status"), why = last.optString("reason");
        if ("failed".equals(state)) status(why.isEmpty() ? "设置未写入眼镜" : "设置未写入：" + why);
        else if ("completed".equals(state) && "sent_awaiting_readback".equals(why)) status("已发送，正在读回确认…");
    }
    /** Shown instead of a control group the glasses have not fully reported yet.
     *
     * <p>Not editable rather than editable-with-defaults: these groups write all their fields in
     * one packet, so guessing one of them would change a setting the user never touched. */
    private void missingGroup(String name) {
        label(name, 14);
        TextView note = shell.text("尚未读到当前值，暂时无法修改。请稍候或点上方「刷新眼镜设置」。", 13);
        note.setTextColor(CompanionShell.MUTED);
        root.addView(note);
    }

    /** Reads one whitelisted settings value, or null when the glasses have not reported it.
     *
     * <p>Returns null rather than a default on purpose. wakeup, crown and head each travel as one
     * packet carrying three fields, so a default substituted for a missing field is not a harmless
     * placeholder -- it is written to the glasses and overwrites a setting the user never touched.
     * GlassesSettingWrite.require() refuses missing fields, but a default filled in here arrives as
     * a perfectly valid integer and it cannot tell the difference. Flagged by independent review
     * 2026-09-22 as the one path that could write a value the user did not choose. */
    private static Integer settingInt(JSONObject values, String path) {
        Object value = values == null ? null : values.opt(path);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }
    /** True when every field of a same-packet group has been reported. */
    private static boolean allPresent(Integer... group) {
        for (Integer value : group) if (value == null) return false;
        return true;
    }

    /** Brightness reaches us two ways: the type1 status snapshot, and the brightness_change the
     * glasses push when they move the level themselves. Neither is authoritative on its own -- the
     * snapshot goes stale the moment the firmware adjusts, and the push only exists once something
     * has changed. The newer of the two is what the glasses are actually showing.
     *
     * <p>Returns null when neither has reported a level, so the caller says so instead of picking
     * a number. The previous code defaulted to 7 here, which is how a missing reading looked
     * exactly like the factory value. */
    private static Integer newerBrightness(JSONObject state, JSONObject status) {
        if (pushWins(state, status))
            return Integer.valueOf(((Number) state.optJSONObject("glasses_brightness").opt("brightness")).intValue());
        return status != null && status.opt("brightness") instanceof Number
            ? Integer.valueOf(((Number) status.opt("brightness")).intValue()) : null;
    }

    /** Whether the level the UI is about to show came from the push rather than the snapshot.
     *
     * <p>The card needs this as well as the number: "眼镜自调" may only be said about a level the
     * glasses actually pushed. The card used to re-derive the same comparison independently, so
     * it could label a snapshot value as self-adjusted whenever a push merely happened to carry
     * lux -- independent review 2026-09-22. One answer, one place. */
    private static boolean pushWins(JSONObject state, JSONObject status) {
        JSONObject pushed = state == null ? null : state.optJSONObject("glasses_brightness");
        if (pushed == null || !(pushed.opt("brightness") instanceof Number)) return false;
        if (status == null || !(status.opt("brightness") instanceof Number)) return true;
        return pushed.optLong("at_ms", 0L) > state.optLong("reported_status_at_ms", 0L);
    }

    interface Chosen { void at(int index); }
    interface Toggled { void to(boolean on); }

    /** A row of buttons rather than a Spinner: the shell has no spinner styling and a dropdown on
     * a dark sheet needs its own theme to stay legible. */
    private void addChoice(String title, String[] labels, int selected, final Chosen chosen) {
        label(title, 14);
        final Button[] buttons = new Button[labels.length];
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout current = row;
        for (int i = 0; i < labels.length; i++) {
            if (i > 0 && i % 4 == 0) {
                root.addView(current);
                current = new LinearLayout(this); current.setOrientation(LinearLayout.HORIZONTAL);
            }
            final int index = i;
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(13);
            button.setAllCaps(false);
            button.setBackground(shell.shape(index == selected ? 0xff3b5bdb : 0xff182b43, 10));
            button.setTextColor(CompanionShell.INK);
            final Button[] group = buttons;
            button.setOnClickListener(v -> {
                for (Button other : group) if (other != null) other.setBackground(shell.shape(0xff182b43, 10));
                v.setBackground(shell.shape(0xff3b5bdb, 10));
                chosen.at(index);
            });
            buttons[i] = button;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            params.setMargins(shell.dp(2), shell.dp(2), shell.dp(2), shell.dp(2));
            current.addView(button, params);
        }
        root.addView(current);
    }

    private void addSwitch(String title, boolean on, final Toggled toggled) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(CompanionShell.INK);
        control.setChecked(on);
        control.setOnCheckedChangeListener((view, checked) -> toggled.to(checked));
        root.addView(control);
    }

    /** Set immediately before an apply-setting command and consumed when the request is written.
     * A field rather than another parameter on the four-argument overload, which already has more
     * positional arguments than is readable. */
    private JSONObject pendingSettingWrite;
    /** Sends one setting change. Refusals come back from the device in last_command.reason -- the
     * ranges are checked there because the CLI can submit the same file. */
    private void writeGlassesSetting(String target, JSONObject fields) {
        try {
            pendingSettingWrite = fields.put("target", target);
            sendSessionCommand("apply-setting", null);
        } catch (Exception e) { pendingSettingWrite = null; status.setText("设置未发出：" + e.getClass().getSimpleName()); }
    }
    private void sendSessionCommand(String kind, JSONObject notification) {
        sendSessionCommand(kind, notification, null, null);
    }
    private void sendSessionCommand(String kind, JSONObject notification, String expectedSession, JSONObject pipeline) {
        if (!CommandWait.allows(kind,busy,false)) return;
        if(CommandWait.interrupt(kind)){
            if(kind.equals("stop"))cancelPhoneJob();
            commandWait.invalidate();busy=false;
        }
        try {
            JSONObject current = session();
            if (!isLive(current)) throw new CloudClient.Failure("请先建立持续 BLE 会话");
            if (notification != null && !AnswerPolicy.canDeliver(notification.optString("content"))) throw new CloudClient.Failure("回答触发危险建议拦截，未发送");
            if (expectedSession != null && !expectedSession.equals(current.optString("session_id"))) throw new CloudClient.Failure("录音后的连接已更换，回答未发送；可手动发送当前结果");
            JSONObject prior = current.optJSONObject("last_command");
            boolean pending=(prior != null && "pending".equals(prior.optString("status"))) || new File(getFilesDir(), "session-command.json").exists();
            if (!CommandWait.allows(kind,busy,pending)) throw new CloudClient.Failure("上一条命令尚未完成");
            String command = UUID.randomUUID().toString(), sessionId = current.getString("session_id");
            JSONObject request = new JSONObject().put("session_id", sessionId).put("command_id", command).put("kind", kind);
            if (notification != null) request.put("notification", notification);
            // apply-setting carries what to write. Validation lives on the device side in
            // GlassesSettingWrite, not here: the CLI can submit the same command file, so a check
            // that only exists in this Activity would not be a check at all.
            if (pendingSettingWrite != null) { request.put("settings", pendingSettingWrite); pendingSettingWrite = null; }
            persist("session-command.json", request);
            final boolean labNative=current.optString("package").equals("dev.xr.rayneo.sdklab")&&kind.equals("voice-native");
            final long ticket=commandWait.begin();
            busy = true; status.setText(labNative?"请唤醒眼镜并提问；说完后自动结束识别，回答显示后可自行退出":(kind.equals("voice-cloud") || kind.equals("voice-native")) ? "请唤醒眼镜并提问；8 秒后上传阿里云识别，回答自动上屏" : kind.equals("audio") ? "直接请求 8 秒麦克风数据，只计数，不保存、不上传" : kind.equals("voice") ? "等待你唤醒眼镜；随后只检查 8 秒音频，不保存、不上传" : kind.equals("pair") ? "等待系统配对结果…" : "已提交眼镜，等待发送回调…");
            // The device side needs the same numbers to tell a working command from an abandoned
            // one, so the table lives in CommandDeadline rather than twice here.
            final long deadline = SystemClock.elapsedRealtime() + CommandDeadline.budgetMs(kind);
            handler.post(new Runnable() { public void run() {
                if (destroyed || !commandWait.current(ticket)) return;
                try {
                    JSONObject latest = session(), cmd = latest.optJSONObject("last_command");
                    if (!sessionId.equals(latest.optString("session_id"))) throw new CloudClient.Failure("会话已更换；发送未确认");
                    JSONObject standby = latest.optJSONObject("standby");
                    if (kind.equals("voice-standby") && standby != null && command.equals(standby.optString("control_id")) && standby.optBoolean("ready")) {
                        busy = false; status.setText(latest.optString("package").equals("dev.xr.rayneo.sdklab")?"眼镜语音待命已开启；读完退出后可再次唤醒，无需再点开始":"眼镜语音待命已开启；每轮回答后可再次唤醒，无需再点开始"); return;
                    }
                    if ((kind.equals("voice-cloud") || kind.equals("voice-native")) && cmd != null && command.equals(cmd.optString("id"))) {
                        JSONObject voice = latest.optJSONObject("voice_test");
                        String phase = voice == null ? "" : voice.optString("phase");
                        status.setText(phase.equals("preparing") || phase.equals("enabling_wakeup") ? "正在准备眼镜语音连接…" : phase.equals("awaiting_wakeup") ? "已就绪：请说“小雷小雷”，再对眼镜提问" : phase.equals("recording") ? (labNative?"眼镜正在收音，说完后自动结束识别":"眼镜正在收音，8秒后自动识别") : phase.equals("decoding") || phase.equals("transcribing") ? "正在解码并调用语音识别…" : "正在处理眼镜语音…");
                    }
                    if (cmd != null && command.equals(cmd.optString("id")) && !"pending".equals(cmd.optString("status"))) {
                        if ((kind.equals("voice-cloud") || kind.equals("voice-native"))) {
                            JSONObject cloud = latest.optJSONObject("glasses_cloud");
                            if (cloud != null && command.equals(cloud.optString("job_id"))) {
                                JSONObject asr = cloud.optJSONObject("asr");
                                answer.setText((asr == null ? "" : "眼镜识别：" + asr.optString("text") + "\n\n") + renderAnswer(cloud));
                            }
                        }
                        if (pipeline != null) { pipeline.put("delivery", cmd).put("lens_verified", false); persist("cloud-result.json", pipeline); }
                        busy = false; status.setText("completed".equals(cmd.optString("status")) ? kind.equals("pair") ? "系统配对与保存成功" : kind.equals("spp") ? "SPP 数据通道已认证" : kind.equals("wake") ? "开启唤醒命令已发送；请进行眼镜语音收发测试" : (kind.equals("voice") || kind.equals("audio")) ? "收到音频且停止请求已发送；尚未接语音识别" : "SDK 发送完成；镜片显示仍需确认" : "本轮测试未通过，请查看连接/眼镜状态"); return;
                    }
                    if (!isLive(latest) || SystemClock.elapsedRealtime() > deadline) throw new CloudClient.Failure("未取得发送完成回调；未自动重试");
                    handler.postDelayed(this, 300);
                } catch (Exception e) { busy = false; status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "读取发送结果失败"); }
            }});
        } catch (Exception e) { status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "无法提交眼镜通知"); }
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == 31 && code == RESULT_OK && data != null && data.getData() != null) startJob("file", data.getData());
        // The associate() callback returning is not proof; read the association back from the system.
        if(request==CompanionRegistration.REQUEST_CODE){String mac=config.optString("glasses_address");
            status.setText(!CompanionRegistration.holds(this,mac)?"未建立设备关联；可重试或在系统设置中检查"
                :CompanionRegistration.observe(this,mac)?"系统设备关联已建立，设备在场观察已开启":"关联已建立，但无法开启设备在场观察");}
        if(request==61&&code==RESULT_OK&&data!=null&&data.getData()!=null&&exportRecording!=null){final File source=exportRecording;final android.net.Uri dest=data.getData();worker.submit(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(dest)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);handler.post(()->status.setText("录音已导出"));}catch(Exception e){handler.post(()->status.setText("导出未完成，手机原件仍保留"));}});}
    }
    @Override protected void onDestroy() {
        if(devicePicker!=null)devicePicker.close();
        destroyed = true; if (recording != null) recording.cancelled = true;
        if(jobCancellation!=null)jobCancellation.cancel();
        jobId = null; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
    @Override protected void onStop() {
        if(devicePicker!=null)devicePicker.close();
        handler.removeCallbacks(refreshLink);
        stopPlayback();
        if (recording != null) {recording.cancelled = true;if(jobCancellation!=null)jobCancellation.cancel();}
        super.onStop();
    }
    @Override protected void onStart() {
        super.onStart(); handler.removeCallbacks(refreshLink);
        if(WeatherSync.enabled(this))ConnectionService.weatherFromVisible(this,false);
        PhoneNotifications.ensureConnected(this);
        if(shell!=null&&!destroyed)handler.post(refreshLink);
    }
}
