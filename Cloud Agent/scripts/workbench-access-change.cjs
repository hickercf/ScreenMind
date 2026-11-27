/* Verify the normal workbench when its own isolated service stops accepting the current key. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL), key = process.env.SCREENMIND_TEST_CONNECTION_KEY;
  assert.ok(['127.0.0.1','localhost'].includes(base.hostname) && key);
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    const name='读书室借用安排', note='讨论结束后把钥匙交给馆员';
    const health=response=>new URL(response.url()).pathname==='/api/health';
    await page.goto(base.href);
    await page.locator('#key-panel:visible').waitFor();
    await page.locator('#api-key').fill(key);
    await page.locator('#save-key').click();
    await page.locator('#connection[data-state="online"]').waitFor();
    await page.locator('#work-name').fill(name);
    await page.locator('#create-work').click();
    await page.locator('#memory-title').filter({hasText:name}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('.screen-top input:checked').length===2);
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    await page.locator('#followup').fill(note);
    const field=page.locator('#card-fields textarea:not([disabled])').first();
    const savedField=await field.inputValue();
    const fieldDraft='我先写下交钥匙的时间';
    await field.fill(fieldDraft);
    const entry = page.locator('.task-item').first();
    const taskTitle = await entry.locator('strong').innerText();
    // First reproduce a dropped connection that still has a valid key.
    await page.route('**/api/health',route=>route.abort('connectionreset'));
    await page.locator('#connection[data-state="offline"]').waitFor();
    assert.equal(await page.locator('#followup').inputValue(),note);
    assert.equal(await field.inputValue(),fieldDraft);
    assert.equal(await page.locator('#card-fields textarea').count(),5);
    await page.unroute('**/api/health');
    await page.locator('#connection[data-state="online"]').waitFor();

    let deniedReads=0;
    await page.route('**/api/health',async route=>{
      const response=await route.fetch({headers:{...route.request().headers(),authorization:'Bearer expired-local-test'}});
      assert.equal(response.status(),401);
      deniedReads++;
      await route.fulfill({response});
    });
    await page.waitForResponse(response=>health(response) && response.status()===401);
    await page.locator('#key-panel:visible').waitFor();
    // Observe two regular poll intervals after the denial. No private data should reappear.
    await page.waitForTimeout(3000);
    const snapshot=await page.evaluate(({name,note,taskTitle,fieldDraft})=>({
      clue_visible:!!document.querySelector('#screen-list article')?.getClientRects().length,
      task_visible:document.body.innerText.includes(taskTitle),work_title_in_dom:document.body.textContent.includes(name),
      card_fields:document.querySelectorAll('#card-fields textarea').length,
      unsent_note_in_form:[...document.querySelectorAll('textarea,input')].some(field=>field.value===note),
      unsent_field_in_dom:document.body.textContent.includes(fieldDraft) ||
        [...document.querySelectorAll('textarea,input')].some(field=>field.value===fieldDraft),
      private_controls_visible:!!document.querySelector('#create-submit')?.getClientRects().length,
    }),{name,note,taskTitle,fieldDraft});
    const report={transient_offline_preserved_draft:true,after_denial:snapshot,denied_health_reads:deniedReads};
    if(out){await page.screenshot({path:path.join(out,'locked.png')});await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));}
    assert.deepEqual(snapshot,{clue_visible:false,task_visible:false,work_title_in_dom:false,card_fields:0,
      unsent_note_in_form:false,unsent_field_in_dom:false,private_controls_visible:false});
    assert.equal(deniedReads,1);
    await page.unroute('**/api/health');
    await page.locator('#api-key').fill(key);
    await page.locator('#save-key').click();
    await page.locator('#connection[data-state="online"]').waitFor();
    await entry.click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    assert.equal(await page.locator('#followup').inputValue(),'');
    assert.equal(await page.locator('#card-fields textarea').count(),5);
    assert.equal(await field.inputValue(),savedField);
    assert.equal(await page.locator('#card-change-notice').isVisible(),false);
    report.reverified_and_reopened_saved_task=true;
    const taskId=await page.locator('#task-view').getAttribute('data-task-id');
    let arrived,release,finished;
    const pending=new Promise(resolve=>{arrived=resolve;});
    const hold=new Promise(resolve=>{release=resolve;});
    const done=new Promise(resolve=>{finished=resolve;});
    await page.route('**/api/tasks/'+taskId,async route=>{
      const response=await route.fetch();
      assert.equal(response.status(),200);
      arrived();
      await hold;
      try {await route.fulfill({response});} catch { /* The former connection was cancelled. */ }
      finished();
    },{times:1});
    await entry.click();
    await pending;
    await page.locator('#key-toggle').click();
    await page.locator('#api-key').fill('expired-local-test');
    await page.locator('#save-key').click();
    await page.locator('#connection').filter({hasText:'需要验证'}).waitFor();
    release(); await done;
    assert.equal(await page.locator('#task-view').isVisible(),false);
    assert.equal(await page.locator('#card-fields textarea').count(),0);
    assert.ok(!(await page.locator('body').textContent()).includes(name));
    assert.deepEqual(errors,[]);
    report.late_authorized_reply_ignored=true;
    if(out)await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log('Temporary disconnection kept the draft. A denied key closed private content until revalidation.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
