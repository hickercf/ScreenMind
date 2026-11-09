/* No HTML from OCR or model output is executed: dynamic content uses textContent. */
const $ = id => document.getElementById(id);
const state = {key: '', works: [], workId: '', agents: [], screens: [], selected: new Set(), tasks: [], current: null, signature: '', events: [], cursor: 0, busy: false, refreshing: false, navigation: 0};
const statuses = {queued:'排队中', running:'执行中', waiting_input:'等待你确认', completed:'已完成', failed:'执行失败', cancelled:'已取消'};
const eventNames = {'router.dispatched':'Router 已分派任务','worker.started':'Worker 开始执行','model.requested':'请求模型','tool.started':'开始调用工具','tool.finished':'工具执行完成','tool.rejected':'工具参数被拒绝','card.published':'交互卡片已发布','worker.finished':'本轮处理完成','worker.failed':'任务执行失败','task.revise':'收到用户补充','task.confirm':'用户确认完成','task.cancel':'用户取消任务','task.retry':'重新排队执行','task.recovered':'恢复中断任务','memory.committed':'事项记忆已更新','work.created':'创建独立事项','screen.received':'接收到屏幕记录'};
const uuid = () => crypto.randomUUID();
function element(tag, cls, text) {const e=document.createElement(tag); if(cls)e.className=cls; if(text!==undefined)e.textContent=text; return e;}
function notice(text, good=false) {$('notice').textContent=text;$('notice').className='notice'+(good?' success':'');$('notice').hidden=false;}
async function api(path, body) {
  const headers = {}; if(state.key)headers.Authorization='Bearer '+state.key;
  if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch('/api/'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body)});
  let data;try{data=await response.json();}catch{throw new Error('服务返回了无效响应');}
  if(!response.ok){if(response.status===401)$('key-panel').hidden=false;throw new Error(typeof data.detail==='string'?data.detail:JSON.stringify(data.detail));}
  return data;
}
function screenTime(value){return new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});}
function renderScreens(){
  $('screen-count').textContent=state.screens.length;
  if(!state.screens.length)return;
  const list=$('screen-list');list.replaceChildren();
  for(const s of state.screens){
    const item=element('article','screen-item'+(state.selected.has(s.id)?' selected':''));
    const label=element('label','screen-top');const check=document.createElement('input');check.type='checkbox';check.checked=state.selected.has(s.id);check.setAttribute('aria-label','选择 '+s.page_title);
    check.onchange=()=>{check.checked?state.selected.add(s.id):state.selected.delete(s.id);item.classList.toggle('selected',check.checked);};
    const title=element('span');title.append(element('strong','',s.page_title||s.app_name),element('small','',s.app_name+' · '+screenTime(s.captured_at)));
    label.append(check,title,element('span','screen-tag',s.blocks.length+' BLOCKS'));
    const body=element('div','screen-body');
    for(const block of s.blocks.slice(0,2))body.append(element('p','',block.text.length>95?block.text.slice(0,95)+'…':block.text));
    const details=element('details');details.append(element('summary','','查看完整文字'));
    for(const block of s.blocks)details.append(element('p','',(block.speaker?block.speaker+'：':'')+block.text));
    details.append(element('span','screen-id',s.id));body.append(details);item.append(label,body);list.append(item);
  }
}
function renderTasks(){
  $('task-count').textContent=String(state.tasks.length).padStart(2,'0');
  if(!state.tasks.length)return;
  const list=$('task-list');list.replaceChildren();
  for(const task of state.tasks){
    const b=element('button','task-item'+(state.current?.id===task.id?' active':''));
    b.append(element('strong','',task.instruction));const info=element('small');info.append(element('span','',statuses[task.status]),element('span','',screenTime(task.created_at)));b.append(info);
    b.onclick=()=>selectTask(task.id).catch(e=>notice(e.message));list.append(b);
  }
}
function sourceDetail(ref){
  if(ref.startsWith('memory:'))return ref+'（本轮读取的事项已确认记忆，历史版本可在上方查看）';
  for(const i of state.current?.inputs||[])if(i.kind==='revise'&&ref==='input:'+state.current.id+':'+(i.expected_revision+1))return '用户补充：'+i.message;
  for(const s of state.screens)for(const b of s.blocks)if(s.id+':'+b.id===ref)return s.app_name+' / '+s.page_title+' / '+screenTime(s.captured_at)+'\n'+b.text;
  return ref+'（来源不在最近 100 条屏幕列表中）';
}
function renderTask(task, force=false){
  const signature=[task.id,task.revision,task.status,task.card_version].join(':');
  state.current=task;
  state.workId=task.work_id||'';renderWorks();
  if(signature===state.signature&&!force)return;
  state.signature=signature;
  $('compose-view').hidden=true;$('task-view').hidden=false;
  $('task-title').textContent='当前任务';$('task-instruction').textContent=task.instruction;
  $('task-routing').textContent='SubAgent：'+task.subagent_id+(task.work_id?' · '+task.work_id:' · 独立任务');
  $('draft-memory').hidden=!task.memory_proposal||task.status!=='waiting_input';$('draft-memory').textContent='候选方案基于记忆 v'+task.memory_base+'，尚未写入事项。确认后生效；取消不会改变已确认计划。';
  $('task-id').textContent=task.id;$('task-revision').textContent='REV '+String(task.revision).padStart(2,'0');
  $('task-status').textContent=statuses[task.status];$('task-status').className='status-pill '+task.status;
  const active=['queued','running'].includes(task.status), ended=['completed','cancelled'].includes(task.status), failed=task.status==='failed';
  $('progress-view').hidden=!active;$('progress-title').textContent=task.status==='queued'?'任务已入队':'Worker 正在处理';
  $('error-view').hidden=!failed;$('error-view').textContent=task.error||'';
  $('card-view').hidden=!task.card;$('followup-area').hidden=ended||failed;$('finished-note').hidden=!ended;
  $('revise').hidden=ended||failed;$('confirm').hidden=task.status!=='waiting_input';$('retry').hidden=!failed;$('cancel').hidden=ended;
  if(task.card){
    $('card-version').textContent='CARD v'+task.card_version+(active?' · 上一版':'');$('card-title').textContent=task.card.title;$('card-summary').textContent=task.card.summary;
    const fields=$('card-fields');fields.replaceChildren();
    for(const f of task.card.fields){
      const row=element('div','field-row');const label=element('label','field-label');label.htmlFor='field-'+f.id;label.append(element('span','',f.label));if(f.origin==='user')label.append(element('span','user-mark','用户修订'));
      const input=document.createElement('textarea');input.id='field-'+f.id;input.rows=2;input.maxLength=1000;input.value=f.value;input.dataset.fieldId=f.id;input.disabled=task.status!=='waiting_input'||f.editable===false;input.setAttribute('aria-label',f.label);
      const details=element('details','source-ref');details.append(element('summary','','↳ '+f.source_refs.length+' 条来源'+(f.origin==='user'?' · 原始值已由用户修订':'')));
      for(const ref of f.source_refs)details.append(element('p','',sourceDetail(ref)));
      row.append(label,input,details);fields.append(row);
    }
  }
  renderTasks();renderEvents();
}
function renderEvents(){
  const events=state.events.filter(e=>e.task_id===state.current?.id).slice(-35);
  const list=$('event-list');list.replaceChildren();
  if(!events.length){list.append(element('li','empty-events','等待任务开始'));return;}
  for(const e of events){
    const item=element('li','event-item'+(e.kind.includes('failed')||e.kind.includes('rejected')?' failed':''));
    item.append(element('time','',new Date(e.at).toLocaleTimeString('zh-CN',{hour12:false})),element('br'),element('strong','',eventNames[e.kind]||e.kind));
    const p=e.payload;const info=[p.tool,p.mode==='mock'?'模拟模型':null,p.step?'STEP '+p.step:null,p.version?'CARD v'+p.version:null,p.revision?'REV '+p.revision:null,p.elapsed_ms!==undefined?p.elapsed_ms+' ms':null,p.error,p.reason].filter(Boolean).join(' · ');
    if(info)item.append(element('p','',info));list.append(item);
  }
}
async function selectTask(id){
  const navigation=++state.navigation;
  const {task}=await api('tasks/'+id);if(navigation!==state.navigation)return;$('followup').value='';$('attach-screens').checked=false;renderTask(task,true);
  const {events}=await api('events?task_id='+encodeURIComponent(id));const bySeq=new Map(state.events.map(e=>[e.seq,e]));events.forEach(e=>bySeq.set(e.seq,e));state.events=[...bySeq.values()].sort((a,b)=>a.seq-b.seq);renderEvents();
}
async function refresh(){
  if(state.refreshing)return;state.refreshing=true;
  try{
    const health=await api('health');$('connection').textContent=health.status==='ok'?'服务在线':'服务异常';
    $('mode-label').textContent=health.provider==='mock'?'模拟模型 · 无外部调用':'真实模型 · '+health.model;
    $('mode-description').textContent=health.provider==='mock'?'验证流程，不代表 AI 分析效果':'结构化数据将发送至配置的模型服务';
    const [{tasks},{events},{works}]=await Promise.all([api('tasks'),api('events?after='+state.cursor),api('works')]);state.works=works;renderWorks();await renderMemory();state.tasks=tasks;renderTasks();
    if(events.length){state.cursor=events.at(-1).seq;const bySeq=new Map(state.events.map(e=>[e.seq,e]));events.forEach(e=>bySeq.set(e.seq,e));state.events=[...bySeq.values()].sort((a,b)=>a.seq-b.seq).slice(-1500);renderEvents();}
    if(state.current&&!state.busy){const id=state.current.id,navigation=state.navigation;const {task}=await api('tasks/'+id);if(state.current?.id===id&&state.navigation===navigation&&!state.busy)renderTask(task);}
  }catch(e){$('connection').textContent='未连接';if(!state.tasks.length)notice(e.message);}finally{state.refreshing=false;}
}
async function guarded(button, fn){
  if(state.busy)return;state.busy=true;button.disabled=true;$('notice').hidden=true;
  try{await fn();}catch(e){notice(e.message);}finally{state.busy=false;button.disabled=false;}
}
const demoScreens=[
  {event_id:'demo-screen-notice-v1',session_id:'demo-registration',app_package:'cn.wps.moffice_eng',app_name:'WPS',page_title:'软件创新大赛 · 报名通知',captured_at:'2026-11-07T10:00:00+08:00',blocks:[
    {id:'deadline',kind:'key_value',text:'校内材料提交截止时间：2026年11月13日（周五）18:00。'},
    {id:'materials',kind:'paragraph',text:'请提交项目计划书、团队参赛证明表和项目演示视频。'},
    {id:'team',kind:'key_value',text:'本示例团队共 3 人，项目名称 ScreenMind。'}]},
  {event_id:'demo-screen-chat-v1',session_id:'demo-registration',app_package:'com.tencent.mm',app_name:'微信',page_title:'项目协作群 · 材料确认',captured_at:'2026-11-07T10:02:00+08:00',blocks:[
    {id:'change',kind:'chat',speaker:'老师',text:'补充通知：计划书请在周四下午 17:00 前发给我预审，正式提交时间不变。'},
    {id:'assignment',kind:'chat',speaker:'我',text:'我负责云端 Super Agent，计划书中的系统架构部分由我整理。'}]}
];
$('load-demo').onclick=()=>guarded($('load-demo'),async()=>{
  state.workId='';state.selected.clear();$('agent-select').value='information';renderWorks();
  for(const body of demoScreens){const result=await api('screens',body);state.selected.add(result.screen.id);}
  state.screens=(await api('screens')).screens;renderScreens();notice('已载入两份合成示例。示例日期与要求不代表正式赛事通知。',true);
});
$('import-toggle').onclick=()=>{$('import-panel').hidden=!$('import-panel').hidden;};
$('import-submit').onclick=()=>guarded($('import-submit'),async()=>{let body;try{body=JSON.parse($('screen-json').value);}catch{throw new Error('JSON 格式无效，请检查括号和引号');}const result=await api('screens',body);state.selected.add(result.screen.id);state.screens=(await api('screens')).screens;renderScreens();$('import-panel').hidden=true;notice('屏幕记录已保存',true);});
$('create-form').onsubmit=e=>{e.preventDefault();guarded($('create-submit'),async()=>{
  if(!state.selected.size)throw new Error('请先载入或导入屏幕，并至少选择一条记录');
  const {task}=await api('tasks',{event_id:uuid(),instruction:$('instruction').value,screen_ids:[...state.selected],work_id:state.workId||null,subagent_id:$('agent-select').value||null});
  state.tasks=(await api('tasks')).tasks;await selectTask(task.id);
});};
function edits(){const result={};for(const input of $('card-fields').querySelectorAll('textarea')){const original=state.current.card.fields.find(f=>f.id===input.dataset.fieldId);if(input.value!==original.value){if(!input.value.trim())throw new Error('字段不能为空');result[original.id]=input.value;}}return result;}
async function act(kind){
  const task=state.current;if(!task)return;
  const body={event_id:uuid(),kind,expected_revision:task.revision};
  if(kind==='revise'){
    body.message=$('followup').value;body.edits=task.status==='waiting_input'?edits():{};
    body.screen_ids=$('attach-screens').checked?[...state.selected].filter(id=>!task.screen_ids.includes(id)):[];
    if(Object.keys(body.edits).length)body.card_version=task.card_version;
    if(!body.message.trim()&&!Object.keys(body.edits).length&&!body.screen_ids.length)throw new Error('请修改字段、填写补充要求，或勾选新屏幕');
  }
  if(kind==='confirm'){
    if(Object.keys(edits()).length||$('followup').value.trim())throw new Error('存在未保存的修改，请先点击“保存修改并继续”');
    body.card_version=task.card_version;
  }
  const result=await api('tasks/'+task.id+'/actions',body);if(state.current?.id===task.id){$('followup').value='';$('attach-screens').checked=false;renderTask(result.task,true);}
}
for(const kind of ['revise','confirm','retry','cancel'])$(kind).onclick=()=>guarded($(kind),()=>act(kind));
$('new-task').onclick=()=>{state.navigation++;state.current=null;state.signature='';$('compose-view').hidden=false;$('task-view').hidden=true;$('notice').hidden=true;renderTasks();renderEvents();};
$('key-toggle').onclick=()=>{$('key-panel').hidden=!$('key-panel').hidden;};
$('save-key').onclick=()=>guarded($('save-key'),async()=>{state.key=$('api-key').value.trim();await api('health');state.screens=(await api('screens')).screens;renderScreens();$('api-key').value='';$('key-panel').hidden=true;await loadAgents();await refresh();notice('已连接服务',true);});
async function init(){try{await loadAgents();}catch(e){notice(e.message);}await refresh();try{state.screens=(await api('screens')).screens;renderScreens();if(state.tasks.length&&state.navigation===0)await selectTask(state.tasks[0].id);}catch(e){notice(e.message);}setInterval(refresh,1200);}
async function loadAgents(){state.agents=(await api('subagents')).subagents;const select=$('agent-select');select.replaceChildren(element('option','','Router 自动分发'));select.firstChild.value='';for(const a of state.agents){const option=element('option','',a.name+' / '+a.id);option.value=a.id;select.append(option);}}
function renderWorks(){
  const select=$('work-select');const signature=JSON.stringify(state.works.map(w=>[w.id,w.title]));
  if(select.dataset.signature!==signature){select.replaceChildren();const empty=element('option','','独立任务（不保存事项记忆）');empty.value='';select.append(empty);for(const w of state.works){const option=element('option','',w.title);option.value=w.id;select.append(option);}select.dataset.signature=signature;}
  select.value=state.workId;const work=state.works.find(w=>w.id===state.workId);$('agent-select').disabled=!!work;if(work)$('agent-select').value=work.subagent_id;
}
async function renderMemory(){
  const wid=state.workId;$('memory-panel').hidden=!wid;if(!wid)return;
  const {work,history}=await api('works/'+wid);if(wid!==state.workId)return;
  $('memory-title').textContent=work.title;$('memory-version').textContent='已确认记忆 v'+work.memory_version;
  const plan=work.state.plan;$('memory-summary').textContent=plan?plan.participants.map(p=>p.name+' · '+({attending:'参加',tentative:'待确认',declined:'退出'}[p.status])).join(' / '):(work.memory_version?'已保存确认后的事项信息。':'尚未确认初始方案，记忆为空。');
  $('memory-json').textContent=JSON.stringify(work.state,null,2);$('memory-history').replaceChildren(...history.map(h=>element('p','muted','v'+h.version+' · '+screenTime(h.at)+' · '+h.task_id)));
  for(const id of ['party-decline','party-tentative']){$(id).hidden=work.subagent_id!=='party';$(id).disabled=!work.memory_version;}
}
$('work-select').onchange=()=>{state.workId=$('work-select').value;state.selected.clear();$('new-task').click();renderWorks();renderScreens();renderMemory().catch(e=>notice(e.message));};
async function newWork(title,agent){const {work}=await api('works',{event_id:uuid(),title,subagent_id:agent});state.works=(await api('works')).works;state.workId=work.id;state.selected.clear();$('new-task').click();renderWorks();renderScreens();await renderMemory();return work;}
$('create-work').onclick=()=>guarded($('create-work'),async()=>{if(!$('work-name').value.trim())throw new Error('请填写事项名称');await newWork($('work-name').value,$('agent-select').value||'party');notice('事项已创建，选择屏幕后可以开始处理。',true);});
function partyScreen(blocks,title){return {event_id:uuid(),session_id:state.workId,app_package:'com.tencent.mm',app_name:'微信 · 合成示例',page_title:title,captured_at:new Date().toISOString(),blocks:blocks.map((b,i)=>({id:'b'+i,kind:'chat',...b}))};}
async function addPartyScreen(body){const {screen}=await api('screens',body);state.selected.clear();state.selected.add(screen.id);state.screens=(await api('screens')).screens;renderScreens();}
$('party-demo').onclick=()=>guarded($('party-demo'),async()=>{
  await newWork('周六生日 Party · '+new Date().toLocaleTimeString('zh-CN'),'party');
  await addPartyScreen(partyScreen(['聚会名称：周六生日 Party','参加成员：小林、小王、小李、小陈、小张、小周、小吴、小赵','时间：本周六 18:30（示例）','地点：湖畔餐厅（示例）','套餐总价：688元','人均预算：150元','蛋糕负责人：小林'].map(text=>({text})),'生日聚会 · 初始安排'));
  $('instruction').value='整理这个 Party 的初始计划，核对人数、预算与分工，等待我确认。';notice('已建立独立聚会。点击“开始处理”，确认初始计划后可模拟成员退出。',true);
});
for(const [id,text] of [['party-decline','我今晚不去了'],['party-tentative','我可能来不了']])$(id).onclick=()=>guarded($(id),async()=>{
  const work=state.works.find(w=>w.id===state.workId);if(!work?.memory_version)throw new Error('请先确认初始计划');
  $('new-task').click();await addPartyScreen(partyScreen([{speaker:'小林',text}],'成员状态变更 · 小林'));
  $('instruction').value='根据这条新消息更新聚会候选方案，检查人数、费用和责任分工的影响，等待我确认。';notice('新消息已载入。点击“开始处理”查看影响分析；原计划保持到你确认。',true);
});
init();
