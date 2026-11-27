/* Two local clients add and withdraw synthetic records while a reader is using the list. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost', '127.0.0.1'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless: true, executablePath: process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport: {width:390,height:844}, locale:'zh-CN'});
    const api = new URL('/api/screens', base).href;
    async function add(title, text) {
      const response = await page.request.post(api, {data: {event_id:randomUUID(), session_id:'library-qa',
        app_package:'cn.example.library', app_name:'读书笔记', page_title:title, captured_at:new Date().toISOString(),
        blocks:[{id:'note',kind:'paragraph',text}]}});
      assert.equal(response.status(),201);
      return (await response.json()).screen.id;
    }
    async function withdraw(id) {
      const response = await page.request.delete(api+'/'+id);
      assert.equal(response.status(),200);
    }
    const eventRead = response => response.request().method()==='GET' && new URL(response.url()).pathname==='/api/events';
    async function throughPoll(operation, id) {
      const delivered = page.waitForResponse(async response => eventRead(response) &&
        (await response.json()).events.some(e=>e.kind==='screen.deleted' && e.payload.screen_id===id));
      await operation();
      await delivered;
      await page.waitForResponse(eventRead);
    }
    const first = await add('周三的借书单','这份借书单稍后撤回');
    const second = await add('周五的读书会','周五在图书馆讨论第三章');
    await page.goto(base.href);
    const list = page.locator('#screen-list');
    const retained = list.locator('article').filter({hasText:'周五的读书会'});
    await retained.getByRole('checkbox').check();
    await retained.locator('summary').click();
    await retained.locator('summary').focus();
    let third;
    await throughPoll(async () => {
      await withdraw(first);
      third = await add('新到的还书提醒','周末闭馆前还书');
    }, first);
    const mid = {titles:await list.locator('article strong').allTextContents(),
      retained_checked:await retained.getByRole('checkbox').isChecked(),
      retained_expanded:await retained.locator('details').evaluate(el=>el.open),
      same_focus:await retained.locator('summary').evaluate(el=>el===document.activeElement)};
    if (out) await list.screenshot({path:path.join(out,'updated.png')});
    await throughPoll(async () => {await withdraw(second);await withdraw(third);}, third);
    const atEnd = async () => ({count:await page.locator('#screen-count').innerText(),cards:await list.locator('article').count(),
      empty:await page.getByText('暂无线索',{exact:true}).isVisible()});
    const empty = await atEnd();
    if (out) await page.locator('.context-panel').screenshot({path:path.join(out,'withdrawn.png')});
    let submissions = 0;
    page.on('request',req=>{if (req.method()==='POST' && new URL(req.url()).pathname==='/api/tasks') submissions++;});
    await page.locator('#create-submit').click();
    await page.locator('#task-create-feedback:visible').waitFor();
    const message = await page.locator('#task-create-feedback').innerText();
    await page.locator('#key-toggle').click();
    await page.locator('#save-key').click();
    await page.locator('#notice').filter({hasText:'已连接服务'}).waitFor();
    const reconnected = await atEnd();
    assert.equal((await (await page.request.get(api)).json()).screens.length,0);
    const report = {mid,empty,reconnected,submissions,message};
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    assert.deepEqual(new Set(mid.titles),new Set(['新到的还书提醒','周五的读书会']));
    assert.ok(mid.retained_checked && mid.retained_expanded && mid.same_focus);
    assert.deepEqual(empty,{count:'0',cards:0,empty:true});
    assert.deepEqual(reconnected,empty);
    assert.equal(submissions,0);
    assert.match(message,/线索/);
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('#screen-list input:checked').length===2);
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    await page.locator('#confirm').click();
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    const note=page.locator('#card-fields [data-source-ref^="scr_"]').first();
    const reference=await note.getAttribute('data-source-ref');
    const source=reference.split(':')[0];
    const disclosure=note.locator('..');
    await disclosure.locator('summary').click();
    assert.notEqual(await note.innerText(),'这条来源目前无法查看。');
    await throughPoll(()=>withdraw(source),source);
    assert.equal(await note.innerText(),'这条来源目前无法查看。');
    assert.equal(await disclosure.evaluate(el=>el.open),true);
    assert.equal(await page.locator('#task-status').innerText(),'已完成');
    report.withdrawn_excerpt_cleared_in_open_completed_card=true;
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log('Updated the live list and open source references, retained focus, and prevented submission of withdrawn records.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error); process.exitCode=1;});
