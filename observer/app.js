const $ = id => document.getElementById(id);
const names = {begin:'启动测试',system_preflight:'系统连接检查',sdk_target_found:'发现眼镜',sdk_first_pairing:'首次配对模式',sdk_callback:'眼镜连接回调',message_submitted:'向 SDK 提交消息',message_send_callback:'SDK 发送完成回调',message_received:'收到眼镜业务消息',bridge_event:'原插件事件',business_observation_started:'开始业务观察',finished:'测试结束'};
function show(state){
 const r=state.result||{}, live=state.live===true;
 $('link').textContent=state.transport==='offline'?'USB 读取中断 · 历史记录':live?(r.status==='sdk_session_ready'?(r.system_bond_state===12&&r.own_saved_target_count===1?'BLE 会话运行 · 已保存系统配对':'BLE 会话运行 · 完整配对待验收'):'测试进行中'):r.status?'测试已结束 · 历史记录':'等待测试';
 $('link').className='badge'+(live?' live':'');
 $('auth').textContent=r.auth_success_callback?(r.spp_auth_success_callback?'BLE / SPP 已认证':r.system_bond_state===12&&r.own_saved_target_count===1?'BLE 已认证 · 系统配对已保存':'BLE 已认证 · 系统配对待完成'):'未取得证据';
 $('sent').textContent=r.text_send_completed?'SDK 发送完成；未证明显示':'未取得证据';
 $('visual').textContent=r.visual_confirmation?.status==='confirmed_complete'?'用户已确认文字完整':'待人工确认';
 const text=r.submitted_text;
 $('title').textContent=text?.title||'等待文字测试'; $('content').textContent=text?.content||'尚未提交测试文字';
 const p=r.reported_page,scene=p?.scene??p?.scence;
 $('page').textContent=p?({0:'仪表盘',1:'应用菜单',2:'应用页面'}[scene]||'收到页面状态')+(p.index!==undefined?' · '+p.index:''):'尚无页面回报';
 $('screen').textContent='屏幕状态：'+(r.screen_raw!==undefined?'原始值 '+r.screen_raw:'未知');
 $('query').textContent='状态查询：'+(r.status_reply_received?'已收到匹配回复':'未收到匹配回复');
 $('device').textContent='电量：'+(r.reported_status?.battery!==undefined?r.reported_status.battery+'%':'未知')+' · 亮度：'+(r.reported_status?.brightness??'未知');
 $('notification').textContent='通知回报：'+(r.notification_reply?'同编号状态 '+JSON.stringify(r.notification_reply):'未收到同编号状态');
 const v=r.voice_test,phase={preparing:'检查语音连接',enabling_wakeup:'开启语音唤醒',awaiting_wakeup:'等待你唤醒眼镜',recording:'正在接收眼镜音频',stopping:'已请求停止，检查后续数据',decoding:'正在解码眼镜音频',transcribing:'正在调用语音识别',sending_answer:'正在发送回答',finished:'本轮已结束',failed:'本轮未通过',cancelled:'本轮已取消'};
 $('voice').textContent='语音测试：'+(v?(v.trigger==='manual_diagnostic'?'直接音频诊断 · ':'')+(phase[v.phase]||v.phase)+' · '+(v.packets||0)+' 包 / '+(v.bytes||0)+' 字节'+(v.phase==='finished'?' · '+(v.transport_test_passed?'收包及停止发送通过':'未通过，查看事件'):''):'尚未开始');
 if(r.standby?.enabled) $('voice').textContent+=' · 待命已开启 / 第 '+r.standby.rounds+' 轮 · 空闲音频包 '+(r.idle_audio_packets||0);
 const cloud=r.glasses_cloud;
 const activeTranscript=r.submitted_transcript, isStreaming=v?.asr_mode==='streaming'&&!['awaiting_wakeup','failed','cancelled'].includes(v?.phase);
 $('transcript').textContent=isStreaming?(activeTranscript?('眼镜识别'+(r.submitted_transcript_final?'（完整）':'（实时）')+'：'+activeTranscript):'正在等待实时识别文字…'):(cloud?.asr?.text?'眼镜识别：'+cloud.asr.text+'（'+cloud.asr.elapsed_ms+' ms）':'');
 const audioInfo=cloud?.duration_ms!==undefined?'眼镜音频 '+(cloud.duration_ms/1000).toFixed(2)+' 秒 · '+(cloud.sample_rate??cloud.asr?.sample_rate??'未知')+' Hz · ':'';
 $('cloud-state').textContent=cloud?audioInfo+(cloud.delivery?.status==='completed'?'回答已发送':cloud.status==='failed'?('本轮结束：'+(cloud.error||'未完成')):'等待回答发送'):'';
 $('outcome').textContent=r.reason||''; $('mode').textContent=r.mode||'';
 $('events').replaceChildren(...(r.stages||[]).slice(-45).reverse().map(e=>{
   const item=document.createElement('li'),time=document.createElement('time'),box=document.createElement('div'),detail=document.createElement('span');
   time.textContent=(e.elapsed_ms/1000).toFixed(2)+' s'; box.textContent=names[e.stage]||e.stage;
   detail.textContent=typeof e.value==='object'?JSON.stringify(e.value):String(e.value);box.append(detail);item.append(time,box);return item;
 }));
}
async function poll(){try{const response=await fetch('/api/state',{cache:'no-store',signal:AbortSignal.timeout(3000)});if(!response.ok)throw Error();show(await response.json());}catch{ $('link').textContent='观察服务不可达 · 画面已过期';$('link').className='badge';}finally{setTimeout(poll,1000);}}
poll();
