/* A save reaches the isolated service and its successful reply is lost. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base=new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost','127.0.0.1'].includes(base.hostname));
  const out=process.env.SCREENMIND_QA_OUTPUT;
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('#screen-list input:checked').length===2);
    await page.locator('#instruction').fill('整理这次读书会的材料');
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const id=await page.locator('#task-view').getAttribute('data-task-id');
    const url=new URL('/api/tasks/'+id,base).href;
    const read=async ()=>(await (await page.request.get(url)).json()).task;
    const note=page.locator('#followup'), save=page.locator('#revise');
    const events=async ()=>(await (await page.request.get(new URL('/api/events?task_id='+id,base).href)).json()).events;
    const write=async (name,value)=>{if(out)await fs.writeFile(path.join(out,name),JSON.stringify(value,null,2));};
    const first='请把图书馆侧门写进集合说明';
    let drop=true;
    const requests=[];
    await page.route(url+'/actions',async route=>{
      const result=await route.fetch();
      const response=await result.json();
      requests.push({id:route.request().postDataJSON().event_id,status:result.status(),replayed:response.replayed});
      if(drop){drop=false;return route.abort('connectionreset');}
      await route.fulfill({response:result});
    });
    async function version(number) {
      await page.waitForFunction(n=>document.querySelector('#card-version').textContent==='第 '+n+' 版' &&
        document.querySelector('#task-status').textContent==='等待你确认',number);
    }
    await note.fill(first);
    await save.click();
    await page.locator('#notice:visible, #task-save-feedback:visible').filter({hasText:/无法连接|没收到|未收到/}).first().waitFor();
    await version(2);
    const afterRead=await note.inputValue();
    await save.click();
    await page.waitForFunction(()=>!document.querySelector('#revise').disabled);
    const saved=(await read()).inputs.filter(i=>i.kind==='revise' && i.message===first).length;
    const report={note_after_server_confirmed:afterRead,identical_revisions:saved,
      worker_runs_including_original:(await events()).filter(e=>e.kind==='worker.started').length,
      accepted_requests:requests.slice()};
    if(out) await page.locator('#followup-area').screenshot({path:path.join(out,'after-recovery.png')});
    await write('result.json',report);
    assert.equal(afterRead,'');
    assert.equal(saved,1);
    assert.equal(requests.length,1);
    assert.equal(report.worker_runs_including_original,2);

    // Hold polling on the previous server state, so the user can retry before any read confirms the save.
    let paused=true;
    await page.route(url,async route=>{
      if(paused) return route.abort('connectionreset');
      await route.continue();
    });
    const next='提醒大家各带一本读过的书';
    drop=true;
    await note.fill(next);
    await save.click();
    const status=page.locator('#task-save-feedback:visible');
    await status.filter({hasText:/没收到|未收到/}).waitFor();
    if(out) await status.screenshot({path:path.join(out,'saving-notice.png')});
    if(out) await status.locator('..').screenshot({path:path.join(out,'retry.png')});
    await save.click();
    await page.waitForFunction(()=>!document.querySelector('#revise').disabled);
    paused=false;
    await version(3);
    assert.equal(await note.inputValue(),'');
    assert.deepEqual(requests.slice(1,3).map(r=>r.replayed),[false,true]);
    assert.equal(requests[1].id,requests[2].id);
    assert.equal((await read()).inputs.filter(i=>i.message===next).length,1);
    // An intentional new submission has its own receipt.
    await note.fill(next);
    await save.click();
    await version(4);
    assert.equal((await read()).inputs.filter(i=>i.message===next).length,2);
    assert.notEqual(requests[2].id,requests[3].id);
    Object.assign(report,{immediate_retry_replayed:true,deliberate_repeat_is_distinct:true,accepted_requests:requests});
    await write('result.json',report);
    console.log('The workbench reconciled the accepted revision and an immediate retry ran it only once.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
