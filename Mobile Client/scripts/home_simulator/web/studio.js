const byId = id => document.getElementById(id);
let catalog = [], run, stepIndex = 0, source = "mock", wireTab = "body", cardTab = "changes", busy = false;
const words = {OFFICE:"事务", TRAVEL:"出行", DINING:"聚餐", SHOPPING:"购物", EXPLORE:"漫游", PROPOSAL:"待确认", CONFIRMED:"已确认", ARCHIVED:"已归档",
  GAP_SUGGESTION:"空档补白", CONFLICT_DASHED:"衔接紧张", ALERT_PULSE:"异常预警", NORMAL_SOLID:"正常衔接",
  ACTIVE_TIMELINE:"今日日程", EMPTY_EXPLORE:"空闲漫游", REST_MODE:"休息"};
const fmt = value => JSON.stringify(value, null, 2);
function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function button(text, click, active) {
  const node = el("button", text, active ? "active" : "");
  node.onclick = click;
  node.disabled = busy;
  return node;
}
async function api(path, body) {
  const resp = await fetch(path, body === undefined ? {cache:"no-store"} : {method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
  if (!resp.ok) {
    const raw = await resp.text();
    let detail; try {detail = JSON.parse(raw).detail;} catch {}
    throw Error(typeof detail === "string" ? detail : "请求失败 · HTTP " + resp.status);
  }
  return resp.json();
}
function message(text = "") { byId("message").textContent = text; }
function clearDevice() {
  byId("device").replaceChildren();
  byId("deviceLabel").textContent = "对接后可读取同一测试账号的最新状态，包括手机上的操作。";
}
async function task(fn) {
  if (busy) return;
  busy = true; render(); message("正在处理…");
  try { await fn(); message(); } catch (e) { message(e.message); }
  finally { busy = false; render(); }
}
function selectCase(id) {
  return task(async () => {
    run = await api("/api/run", {case:id});
    source = "mock"; stepIndex = run.mock.length - 1; cardTab = "changes"; clearDevice();
  });
}
function allCards(doc) {
  return [...doc.archivedEvents, ...doc.events, ...(doc.crossDayRelay ? [doc.crossDayRelay] : [])];
}
function inputFor(card) {
  const frames = run[source];
  return frames.find(f => f.path === "/v1/events" && f.body.event_id === card.sourceEventId)?.body;
}
function jsonSection(parent, label, data) {
  const block = el("details"); block.append(el("summary", label), el("pre", fmt(data))); parent.append(block);
}
function cardRow(card, doc, change, detail) {
  const row = el("details", undefined, "row");
  const parts = [change?.kind, card.time, card.title].filter(Boolean);
  row.append(el("summary", parts.join(" · ")));
  const input = inputFor(card);
  const origin = input ? ({calendar:"系统日历",screenshot_ocr:"截图 OCR",manual:"手动线索", location:"定位"}[input.source_kind])
    : card.sourceTaskId ? "云端任务候选" : card.sourceWorkId ? "云端事项" : doc.showcaseMode ? "场景模板或交互生成" : "已同步线索";
  const position = doc.crossDayRelay?.id === card.id ? "跨日接力" : doc.archivedEvents.some(a=>a.id===card.id) ? "归档区" : "时间轴";
  row.append(el("p", [origin, words[card.category] || card.category, words[card.lifecycleStage] || card.lifecycleStage, position].filter(Boolean).join(" → ")));
  if (card.connectorToNext) row.append(el("p", "连接规则 · " + (words[card.connectorToNext.connectorState] || "") + " · " + card.connectorToNext.label));
  if (card.detailBlocks?.length) row.append(el("p", "详情组件 · " + card.detailBlocks.join(" / ")));
  if (change) row.append(el("p", "变动字段 · " + change.fields.join(", ")));
  if (input) jsonSection(row, "原始输入", input);
  if (detail) jsonSection(row, "1.2 详情接口 · " + detail.detailBlocks.map(b=>b.blockType).join(" / "), detail);
  if (change?.before) jsonSection(row, "变更前 JSON", change.before);
  jsonSection(row, change?.kind === "移除" ? "移除后的结果" : "下发卡片 JSON", change && change.after === null ? null : card);
  return row;
}
function render() {
  if (!run) return;
  const info = catalog.find(x => x.id === run.case);
  byId("count").textContent = catalog.length;
  byId("cases").replaceChildren(); let group;
  for (const item of catalog) {
    if (item.group !== group) { group = item.group; byId("cases").append(el("h3",group)); }
    byId("cases").append(button(item.title, () => selectCase(item.id), item.id === run.case));
  }
  byId("group").textContent = info.group; byId("title").textContent = info.title; byId("description").textContent = info.detail;
  byId("steps").replaceChildren();
  run.mock.forEach((frame, i) => {
    const b = button("", () => {stepIndex=i;if (!run.remote[i]) source="mock";message();render();}, i===stepIndex);
    b.append(el("small",String(i+1).padStart(2,"0")),document.createTextNode(frame.title));
    byId("steps").append(b);
  });
  const remoteExists = !!run.remote[stepIndex];
  byId("mock").classList.toggle("active",source==="mock"); byId("remote").classList.toggle("active",source==="remote");
  byId("remote").disabled = busy || !remoteExists; byId("send").disabled = busy || remoteExists || run.interrupted;
  byId("send").textContent = remoteExists ? "已发送至本地后端" : "发送至本步 →";
  byId("refresh").disabled = busy || !run.remote.length; byId("repeat").disabled=busy;
  const frame = run[source][stepIndex], doc = frame.doc;
  byId("operation").textContent = frame.title; byId("rule").textContent = frame.rule;
  byId("mode").textContent = (words[frame.previousMode] || frame.previousMode) + " → " + (words[doc.dayMode] || doc.dayMode);
  byId("status").textContent = (source === "remote" ? "HTTP " : "ASGI ") + frame.status;
  byId("method").textContent = wireTab === "contract" ? "GET /api/v1/home/timeline" : frame.method + " " + frame.path;
  byId("wirelabel").textContent = ({body:"本步提交的完整 JSON",reply:"接口返回的原始响应",contract:"1.2 首页时间轴，包括焦点、归档、跨日与漫游"})[wireTab];
  byId("wire").textContent = fmt(frame[wireTab]);
  document.querySelectorAll("[data-wire]").forEach(b=>b.classList.toggle("active", b.dataset.wire===wireTab));
  document.querySelectorAll("[data-cards]").forEach(b=>b.classList.toggle("active", b.dataset.cards===cardTab));
  const cards = allCards(doc);
  byId("total").textContent = frame.changes.length + " 变化 / " + cards.length + " 事件";
  const rows = cardTab === "all" ? cards.map(c=>cardRow(c,doc,null,frame.details[c.id])) : frame.changes.map(c=>cardRow(c.after || c.before,doc,c,frame.details[c.id]));
  byId("cards").replaceChildren(...(rows.length ? rows : [el("p",cardTab==="all" ? "当前没有事件卡片。" : "本步没有增加、修改或移除事件卡片。首页模式与环境字段可在「首页下发」中查看。","caption")]));
}
byId("wiretabs").onclick=e=>{if(e.target.dataset.wire){wireTab=e.target.dataset.wire;render();}};
byId("cardtabs").onclick=e=>{if(e.target.dataset.cards){cardTab=e.target.dataset.cards;render();}};
byId("mock").onclick=()=>{source="mock";render();};byId("remote").onclick=()=>{source="remote";render();};
byId("repeat").onclick=()=>selectCase(run.case);
byId("send").onclick=()=>task(async()=>{run=await api("/api/connect",{run:run.id,through:stepIndex});source="remote";clearDevice();});
byId("refresh").onclick=()=>task(async()=>{
  const doc = await api("/api/current"), cards=allCards(doc);
  byId("deviceLabel").textContent=new Date().toLocaleTimeString()+" · "+(words[doc.dayMode] || doc.dayMode)+" · "+cards.length+" 项事件";
  byId("device").replaceChildren(...cards.map(c=>cardRow(c,doc)));jsonSection(byId("device"),"完整网关响应",doc);
});
(async()=>{try {
  catalog=await api("/api/catalog");run=await api("/api/state");stepIndex=run.remote.length ? run.remote.length-1 : run.mock.length-1;
  source=run.remote.length ? "remote":"mock";if(innerWidth<680) byId("directory").open=false;render();
} catch(e){message(e.message);}})();
