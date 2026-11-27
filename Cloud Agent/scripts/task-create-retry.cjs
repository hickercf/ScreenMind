/* Use the isolated service to drop or delay an accepted task's HTTP reply. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost', '127.0.0.1'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    const read = async resource => (await page.request.get(new URL('/api/'+resource,base).href)).json();
    const receipts = [];
    let delay;
    function delayOne() {
      let arrived, release;
      const accepted = new Promise(resolve => { arrived = resolve; });
      const resume = new Promise(resolve => { release = resolve; });
      delay = {arrived,resume};
      return {accepted,release};
    }
    await page.route('**/api/tasks', async route => {
      if (route.request().method() !== 'POST') return route.continue();
      const response = await route.fetch();
      assert.equal(response.status(),202);
      const {task,replayed} = await response.json();
      receipts.push({request:route.request().postDataJSON().event_id,task:task.id,replayed});
      if (receipts.length === 1) return route.abort('connectionreset');
      if (delay) {
        const pending = delay;
        delay = null;
        pending.arrived();
        await pending.resume;
      }
      await route.fulfill({response});
    });
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(() => document.querySelectorAll('#screen-list input:checked').length===2);
    const input=page.locator('#instruction'), start=page.locator('#create-submit');
    const note='整理周四要交给老师的材料';
    await input.fill(note);
    await start.click();
    await page.locator('#task-create-feedback:visible, #notice:visible').filter({hasText:/无法连接|断开|重试/}).waitFor();
    assert.equal(await input.inputValue(),note);
    if (out) await page.screenshot({path:path.join(out,'interrupted.png')});
    await start.click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const saved = (await read('tasks')).tasks.filter(t=>t.instruction===note);
    const allEvents = (await read('events')).events;
    const report = {accepted:receipts.slice(),saved_tasks:saved.length,
      worker_starts:allEvents.filter(e=>saved.some(t=>t.id===e.task_id)&&e.kind==='worker.started').length};
    const save = async () => {if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));};
    await save();
    assert.deepEqual(receipts.map(r=>r.replayed),[false,true]);
    assert.equal(report.saved_tasks,1);
    assert.equal(report.worker_starts,1);
    assert.equal(await page.locator('#task-view').getAttribute('data-task-id'),saved[0].id);
    if(out) await page.locator('.task-panel').screenshot({path:path.join(out,'recovered.png')});
    await page.locator('#new-task').click();
    await input.fill(note);
    await start.click();
    await page.waitForFunction(first=>{const panel=document.querySelector('#task-view');return !panel.hidden&&panel.dataset.taskId!==first;},saved[0].id);
    assert.equal((await read('tasks')).tasks.filter(t=>t.instruction===note).length,2);
    const second=receipts[2].task;
    assert.notEqual(receipts[1].request,receipts[2].request);
    await page.locator('#new-task').click();
    const delayedEdit=delayOne();
    await input.fill('列出周四的截止时间');
    await start.click();
    await delayedEdit.accepted;
    await input.fill('另外列出周五的安排');
    delayedEdit.release();
    await page.waitForFunction(()=>!document.querySelector('#create-submit').disabled);
    assert.ok(await input.isVisible());
    assert.equal(await input.inputValue(),'另外列出周五的安排');
    const delayedSelection=delayOne();
    await start.click();
    await delayedSelection.accepted;
    const oldTask=page.locator('.task-item').filter({hasText:'周四要交给老师的材料'}).first();
    await oldTask.click();
    await page.locator('#task-view:visible').waitFor();
    const selected=await page.locator('#task-view').getAttribute('data-task-id');
    assert.ok([saved[0].id,second].includes(selected));
    delayedSelection.release();
    await page.waitForFunction(()=>!document.querySelector('#create-submit').disabled);
    assert.equal(await page.locator('#task-view').getAttribute('data-task-id'),selected);
    Object.assign(report,{deliberate_same_instruction:2,editing_preserved:true,navigation_preserved:true});
    await save();
    console.log('A lost acknowledgment ran one task once. Separate submissions, editing and navigation were preserved.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
