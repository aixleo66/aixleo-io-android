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
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, answer, link;
    private EditText question;
    private Switch streaming,knowledgeVoice;
    private AnswerResult currentAnswer=AnswerResult.empty();
    private final CommandWait commandWait=new CommandWait();
    private CloudClient.Cancellation jobCancellation;
    private String renderAnswer(JSONObject value){
        JSONObject body=value.optJSONObject("answer");if(body==null)body=value;
        currentAnswer=new AnswerResult(body.optString("text",value.optString("text")),body.optString("lens_text"));
        StringBuilder text=new StringBuilder(currentAnswer.text);
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
    private TextView assistantState, deviceState;
    private TextView recordingState;
    private Button recordStart,recordStop;
    private LinearLayout recordingList;
    private android.media.MediaPlayer player;
    private File exportRecording;
    private String librarySignature=null;
    private void playRecording(File file){
        stopPlayback();
        try{player=new android.media.MediaPlayer();android.media.MediaPlayer current=player;
            current.setDataSource(file.getAbsolutePath());current.setOnPreparedListener(p->{if(player==p){p.start();status.setText("正在用手机播放录音");}});
            current.setOnCompletionListener(p->{if(player==p){stopPlayback();status.setText("录音播放结束");}});
            current.setOnErrorListener((p,a,b)->{if(player==p){stopPlayback();status.setText("音频播放失败，文件已保留");}return true;});current.prepareAsync();
        }catch(Exception e){stopPlayback();status.setText("无法打开录音文件");}
    }
    private void stopPlayback(){if(player!=null){player.release();player=null;}}
    private void refreshRecordings(JSONObject session){
        JSONObject r=isLive(session)?session.optJSONObject("recording"):null;String phase=r==null?"idle":r.optString("phase");
        boolean active=Arrays.asList("connecting","starting","recording","stopping","saving").contains(phase);
        String title=phase.equals("connecting")?"正在准备录音连接":phase.equals("starting")?"等待眼镜确认开始":phase.equals("recording")?"正在录音":phase.equals("stopping")?"已请求停止，等待尾部数据":phase.equals("saving")?"正在校验与解码":phase.equals("saved")?"已保存到手机":phase.equals("failed")?"录音未完整保存":phase.equals("cancelled")?"已取消开始录音":"准备录音";
        String detail=r==null?"连接眼镜后点击开始。":phase.equals("failed")?r.optString("error"):phase.equals("saved")?"可在下方播放或导出。":"音频仅保存在这台手机。";
        if(r!=null&&phase.equals("recording")&&r.optLong("confirmed_at_ms")>0)detail="录音计时约 "+Math.max(0,(System.currentTimeMillis()-r.optLong("confirmed_at_ms"))/1000)+" 秒 · "+detail;
        recordingState.setText(title+"\n\n"+detail);recordStart.setEnabled(!active&&!busy);recordStop.setEnabled(active&&!phase.equals("saving"));
        File dir=new File(getFilesDir(),"recordings");File[] folders=dir.listFiles(File::isDirectory);if(folders==null)folders=new File[0];
        Arrays.sort(folders,(a,b)->Long.compare(b.lastModified(),a.lastModified()));StringBuilder sig=new StringBuilder();
        for(File folder:folders)sig.append(folder.getName()).append(new File(folder,"receipt.json").lastModified());
        if(sig.toString().equals(librarySignature))return;librarySignature=sig.toString();recordingList.removeAllViews();
        if(folders.length==0){TextView t=shell.text("暂无录音文件",14);recordingList.addView(t);}
        int count=0;for(File folder:folders){if(++count>100)break;try{
            File receipt=new File(folder,"receipt.json");if(!receipt.isFile())continue;
            JSONObject meta=new JSONObject(new String(CloudConfig.read(new FileInputStream(receipt),65536),"UTF-8"));
            String label=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(meta.optLong("created_at_ms")));
            TextView t=shell.text(label+" · "+("saved".equals(meta.optString("phase"))?meta.optLong("duration_ms")/1000.0+" 秒":"未完成 · 原始数据保留"),16);t.setPadding(0,20,0,8);recordingList.addView(t);
            File wav=new File(folder,"recording.wav");if("saved".equals(meta.optString("phase"))&&wav.isFile()){
                LinearLayout row=new LinearLayout(this);Button play=new Button(this);play.setText("播放");play.setOnClickListener(v->playRecording(wav));row.addView(play,new LinearLayout.LayoutParams(0,-2,1));
                Button share=new Button(this);share.setText("导出 WAV");share.setOnClickListener(v->{exportRecording=wav;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("audio/wav").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"雷鸟录音-"+folder.getName().substring(0,8)+".wav"),61);});row.addView(share,new LinearLayout.LayoutParams(0,-2,1));recordingList.addView(row);
            }
        }catch(Exception ignored){TextView t=shell.text("有一份录音目录暂时无法读取，原件保留",14);recordingList.addView(t);}}
    }
    private String displayedCloudId = "";

    private void buildCompanionUi() {
        shell = new CompanionShell(this); status=shell.feedback; link=shell.connection;
        shell.title(0,"随身录音","用眼镜收音，录音保存在手机。");
        recordingState=shell.card(0,"准备录音\n\n连接眼镜后点击开始。");
        recordStart=shell.action(0,"开始录音",true,()->{stopPlayback();sendSessionCommand("record-start",null);});
        recordStop=shell.action(0,"结束并保存",false,()->sendSessionCommand("record-stop",null));recordStop.setEnabled(false);
        shell.note(0,"本版每段最多 5 分钟。原始音频只存手机，不上传；录音会暂停语音待命，保存后自动恢复已开启的待命。");
        shell.section(0,"我的录音");recordingList=new LinearLayout(this);recordingList.setOrientation(1);shell.pages[0].addView(recordingList);
        shell.action(0,"停止手机播放",false,this::stopPlayback);
        shell.action(0,"查看设备连接",false,()->shell.show(3));

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
        shell.action(1,"测试知识库问答",false,()->startJob("knowledge-test",null));
        shell.action(1,"配置知识库与助手服务",false,()->shell.show(4));
        shell.action(1,"取消手机任务",false,()->{cancelPhoneJob();status.setText("已取消本机任务，不再继续后续请求；已提交的远端任务可能仍在结束");});

        notificationSettings=new NotificationSettingsUi(this,shell);

        shell.title(3,"设备","连接和服务设置，都在这里。");deviceState=shell.card(3,"RayNeo iO\n正在读取连接状态…");
        shell.action(3,"连接眼镜",true,this::connectGlasses);
        shell.action(3,"断开眼镜",false,()->{getSharedPreferences("assistant",MODE_PRIVATE).edit().putBoolean("auto_connect",false).apply();sendSessionCommand("stop",null);});
        shell.action(3,"模型与语音服务",false,()->shell.show(4));
        shell.action(3,"显示与自动退出",false,()->shell.show(5));
        shell.action(3,"开发者诊断",false,()->shell.show(6));
        shell.note(3,"保存的服务配置继续保留。连接仍使用已验证的 BLE 路径。");

        shell.action(4,"‹ 返回设备",false,()->shell.show(3));shell.title(4,"服务配置","Key 加密保存在本机，留空保留已有值。");root=shell.pages[4];
        field("deepseek_key","DeepSeek Key",true);field("deepseek_url","DeepSeek 接口地址",false);field("deepseek_model","对话模型",false);
        field("dashscope_key","阿里云 DashScope Key",true);field("dashscope_url","阿里云文件转写地址",false);field("dashscope_model","文件转写模型",false);
        field("dashscope_stream_url","实时识别 WSS 地址",false);field("dashscope_stream_model","实时识别模型",false);field("glasses_address","眼镜蓝牙地址",false);
        field("knowledge_url","知识库 WSS 地址",false);field("knowledge_token","知识库 Token",true);
        knowledgeVoice=new Switch(this);knowledgeVoice.setText("语音与默认文字提问使用知识库");knowledgeVoice.setTextColor(CompanionShell.INK);knowledgeVoice.setChecked(config.optString("assistant_provider","deepseek").equals("knowledge"));root.addView(knowledgeVoice);
        shell.note(4,"关闭时使用 DeepSeek。知识库不可用时会报告失败，不自动把问题转给其他服务。临时隧道重启后可在此更新地址。");
        streaming=new Switch(this);streaming.setText("实时识别，边说边显示提问");streaming.setTextColor(CompanionShell.INK);streaming.setChecked(config.optBoolean("streaming_asr"));root.addView(streaming);
        shell.action(4,"保存服务配置",true,this::save);

        shell.action(5,"‹ 返回设备",false,()->shell.show(3));shell.title(5,"显示与退出","当前生效规则，暂未开放自定义。");
        shell.card(5,"回答显示：发送完成后约 15 秒退出\n\n实时识别：无语音约 8 秒结束收音\n\n眼镜电量与系统时间：尚未在此页读取");
        shell.note(5,"显示时长、消息正文长度等选项将随对应功能接入；这里没有无效的设置开关。");

        shell.action(6,"‹ 返回设备",false,()->shell.show(3));shell.title(6,"开发者诊断","主动操作才会执行测试。");root=shell.pages[6];
        button("完成系统配对（首次使用）",()->sendSessionCommand("pair",null));button("连接语音数据通道（SPP）",()->sendSessionCommand("spp",null));button("开启眼镜语音唤醒",()->sendSessionCommand("wake",null));
        button("眼镜单轮问答",()->{if(save())sendSessionCommand("voice-native",null);});button("眼镜语音数据诊断",()->sendSessionCommand("voice",null));button("直接检查 8 秒麦克风（不上传）",()->sendSessionCommand("audio",null));
        button("手机麦克风备用诊断（8 秒）",this::startPhoneVoice);button("结束手机诊断并识别",()->{if(recording!=null)recording.stop=true;});button("取消手机诊断与后续请求",this::cancelRecording);
        button("验证阿里云 ASR（上传预置语音）",()->startJob("sample",null));button("选择音频上传转写",()->{if(busy)return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE),31);});
        shell.note(6,"诊断结果显示在助手页；手机录音诊断不产生录音文件。");shell.action(6,"查看最近结果",false,()->shell.show(1));
        shell.show(0);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }
    private void connectGlasses() {
        if(busy||!save())return;
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED
            ||checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)!=android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_SCAN},51);
            status.setText("请允许附近设备权限以连接眼镜");return;
        }
        try {
            if(isLive(session())){status.setText("眼镜连接已就绪");return;}
            if(SdkProbeActivity.connectionActive){status.setText("正在连接眼镜，请稍候");return;}
            String address=config.optString("glasses_address");
            if(!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)){status.setText("请先在服务配置填写眼镜蓝牙地址");shell.show(4);return;}
            startActivity(new Intent(this,SdkProbeActivity.class).putExtra("target_address",address).putExtra("connect",true).putExtra("pairing_ready",true).putExtra("business_probe","session").putExtra("session_seconds",1800).putExtra("companion_ui",true));
        }catch(Exception e){status.setText("无法读取连接状态");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==51){boolean allowed=grants.length==2;for(int grant:grants)allowed&=grant==android.content.pm.PackageManager.PERMISSION_GRANTED;
            if(allowed)connectGlasses();else status.setText("附近设备权限未开启；可再次点击连接授权");}
    }
    private void handleBack(){if(shell!=null&&shell.selected!=0){shell.show(shell.selected>=4?3:0);return;}moveTaskToBack(true);}
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
            try{if(!isLive(session()))connectGlasses();}catch(Exception ignored){}
        });
        try {
            File prior = new File(getFilesDir(), "cloud-result.json");
            if (prior.isFile()) {
                JSONObject saved = new JSONObject(new String(CloudConfig.read(new FileInputStream(prior), 1048576), "UTF-8"));
                if ("completed".equals(saved.optString("status"))) {
                    String restored = saved.getString("text");
                    if (AnswerPolicy.canDeliver(restored)) {
                        answer.setText(renderAnswer(saved));
                        status.setText("已恢复上次 " + saved.optString("provider") + " 结果；尚未再次发送");
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
                if (!(entry.getKey().endsWith("_key")||entry.getKey().endsWith("_token")) || !value.isEmpty()) next.put(entry.getKey(), value);
            }
            next.put("streaming_asr", streaming.isChecked()).put("assistant_provider",knowledgeVoice.isChecked()?"knowledge":"deepseek"); StreamingAsr.endpoint(next);
            CloudConfig.save(this, next); config = next;
            for (String name : new String[]{"deepseek_key", "dashscope_key","knowledge_token"}) {
                fields.get(name).setText(""); fields.get(name).setHint(config.optString(name).isEmpty() ? "输入 API Key" : "已配置；留空保留，输入新 Key 可替换");
            }
            status.setText("服务配置已保存"); return true;
        } catch (Exception e) { status.setText(e instanceof CloudClient.Failure ? e.getMessage() : "配置保存失败；未输出密钥"); return false; }
    }
    private void startJob(String kind, android.net.Uri uri) {
        if (busy || !save()) return;
        final JSONObject settings;
        try { settings = new JSONObject(config.toString()); } catch (Exception e) { return; }
        final String prompt = kind.equals("knowledge-test")?"Rokid 正式版 spokenAnswer 和 displayAnswer 分别如何使用？请根据知识库回答。":question.getText().toString();
        final CloudClient.Cancellation cancel=new CloudClient.Cancellation();jobCancellation=cancel;
        busy = true; clearAnswer(); jobId = UUID.randomUUID().toString(); final String id = jobId;
        answer.setText("等待服务响应…"); status.setText(kind.startsWith("knowledge")||kind.equals("text")&&settings.optString("assistant_provider").equals("knowledge")?"正在查询远端知识库…":kind.equals("text") ? "正在请求 DeepSeek…" : "手机正在上传测试音频到阿里云…");
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
        try{JSONObject cloud=session().optJSONObject("glasses_cloud");if(cloud!=null)displayedCloudId=cloud.optString("job_id");}catch(Exception ignored){}
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
            if (config.optString("dashscope_key").isEmpty() || config.optString("deepseek_key").isEmpty())
                throw new CloudClient.Failure("请先保存阿里云和 DeepSeek Key");
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
                    answer.setText("识别：" + transcript); status.setText("DeepSeek 正在回答…");
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
    private JSONObject session() throws Exception {
        File file = new File(getFilesDir(), "result.json");
        return file.exists() ? new JSONObject(new String(CloudConfig.read(new FileInputStream(file), 262144), "UTF-8")) : new JSONObject();
    }
    private boolean isLive(JSONObject value) {
        return value.optInt("pid") == android.os.Process.myPid() && "sdk_session_ready".equals(value.optString("status")) && value.optBoolean("auth_success_callback");
    }
    private final Runnable refreshLink = new Runnable() { public void run() {
        if (destroyed || link == null) return;
        try { JSONObject state = session(); boolean live=isLive(state);
            link.setText(live ? "眼镜已连接 · BLE 已认证" : "眼镜未连接 · 可前往设备页连接");
            if(shell!=null){
                if(notificationSettings!=null)notificationSettings.refresh();
                refreshRecordings(state);
                deviceState.setText("RayNeo iO\n\n"+(live?"业务连接已认证":"当前没有有效业务连接")+"\n电量：未获取");
                JSONObject standby=state.optJSONObject("standby"), voice=state.optJSONObject("voice_test");
                String phase=voice==null?"":voice.optString("phase");
                String title=!live?"等待连接眼镜":standby!=null&&standby.optBoolean("ready")?"已待命 · 说“小雷小雷”":"待命未开启";
                if(live&&standby!=null&&standby.optBoolean("enabled")){
                    if("recording".equals(phase))title="正在听你说";
                    else if("answering".equals(phase)||"transcribing".equals(phase)||"finalizing_transcript".equals(phase))title="正在处理你的问题";
                    else if("sending_answer".equals(phase))title="正在向眼镜发送回答";
                }
                assistantState.setText(title);
                JSONObject cloud=state.optJSONObject("glasses_cloud");
                if(!busy&&live&&cloud!=null&&"completed".equals(cloud.optString("status"))&&!cloud.optString("job_id").equals(displayedCloudId)){
                    String text=cloud.optString("text");if(AnswerPolicy.canDeliver(text)){
                        displayedCloudId=cloud.optString("job_id");JSONObject asr=cloud.optJSONObject("asr");
                        answer.setText((asr==null?"":"提问："+asr.optString("text")+"\n\n")+renderAnswer(cloud));
                    }
                }
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
            persist("session-command.json", request);
            final long ticket=commandWait.begin();
            busy = true; status.setText((kind.equals("voice-cloud") || kind.equals("voice-native")) ? "请唤醒眼镜并提问；8 秒后上传阿里云识别，回答自动上屏" : kind.equals("audio") ? "直接请求 8 秒麦克风数据，只计数，不保存、不上传" : kind.equals("voice") ? "等待你唤醒眼镜；随后只检查 8 秒音频，不保存、不上传" : kind.equals("pair") ? "等待系统配对结果…" : "已提交眼镜，等待发送回调…");
            final long deadline = SystemClock.elapsedRealtime() + (kind.equals("record-stop")?180000:kind.equals("record-start")?40000:(kind.equals("voice-cloud") || kind.equals("voice-native")) ? 240000 : kind.equals("voice-standby") ? 40000 : kind.equals("voice") ? 103000 : kind.equals("pair") ? 70000 : kind.equals("spp") ? 28000 : kind.equals("audio") ? 15000 : 12000);
            handler.post(new Runnable() { public void run() {
                if (destroyed || !commandWait.current(ticket)) return;
                try {
                    JSONObject latest = session(), cmd = latest.optJSONObject("last_command");
                    if (!sessionId.equals(latest.optString("session_id"))) throw new CloudClient.Failure("会话已更换；发送未确认");
                    JSONObject standby = latest.optJSONObject("standby");
                    if (kind.equals("voice-standby") && standby != null && command.equals(standby.optString("control_id")) && standby.optBoolean("ready")) {
                        busy = false; status.setText("眼镜语音待命已开启；每轮回答后可再次唤醒，无需再点开始"); return;
                    }
                    if ((kind.equals("voice-cloud") || kind.equals("voice-native")) && cmd != null && command.equals(cmd.optString("id"))) {
                        JSONObject voice = latest.optJSONObject("voice_test");
                        String phase = voice == null ? "" : voice.optString("phase");
                        status.setText(phase.equals("preparing") || phase.equals("enabling_wakeup") ? "正在准备眼镜语音连接…" : phase.equals("awaiting_wakeup") ? "已就绪：请说“小雷小雷”，再对眼镜提问" : phase.equals("recording") ? "眼镜正在收音，8秒后自动识别" : phase.equals("decoding") || phase.equals("transcribing") ? "正在解码并调用语音识别…" : "正在处理眼镜语音…");
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
        if(request==61&&code==RESULT_OK&&data!=null&&data.getData()!=null&&exportRecording!=null){final File source=exportRecording;final android.net.Uri dest=data.getData();worker.submit(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(dest)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);handler.post(()->status.setText("录音已导出"));}catch(Exception e){handler.post(()->status.setText("导出未完成，手机原件仍保留"));}});}
    }
    @Override protected void onDestroy() {
        destroyed = true; if (recording != null) recording.cancelled = true;
        if(jobCancellation!=null)jobCancellation.cancel();
        jobId = null; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
    @Override protected void onStop() {
        handler.removeCallbacks(refreshLink);
        stopPlayback();
        if (recording != null) {recording.cancelled = true;if(jobCancellation!=null)jobCancellation.cancel();}
        super.onStop();
    }
    @Override protected void onStart() {
        super.onStart(); handler.removeCallbacks(refreshLink);
        PhoneNotifications.ensureConnected(this);
        if(shell!=null&&!destroyed)handler.post(refreshLink);
    }
}
