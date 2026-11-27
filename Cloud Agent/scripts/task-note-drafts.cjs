/* Navigate two real tasks, then keep typing while one saved response is in transit. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['127.0.0.1','localhost'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    const note = page.locator('#followup');
    const firstTitle = '整理图书馆的借阅材料', secondTitle = '核对去阅览室的时间';
    const firstNote = '周六我可以提早半小时到图书馆', secondNote = '另一份材料里有阅览室的钥匙';
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('#screen-list input:checked').length===2);
    async function create(title) {
      await page.locator('#instruction').fill(title);
      await page.locator('#create-submit').click();
      await page.locator('#task-instruction').filter({hasText:title}).waitFor();
      await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
      return page.locator('#task-view').getAttribute('data-task-id');
    }
    async function open(title) {
      await page.locator('.task-item').filter({hasText:title}).click();
      await page.locator('#task-instruction').filter({hasText:title}).waitFor();
    }
    const first = await create(firstTitle);
    await note.fill(firstNote);
    await page.locator('#new-task').click();
    await create(secondTitle);
    assert.equal(await note.inputValue(),'');
    await note.fill(secondNote);
    await open(firstTitle);
    const report = {first_on_return:await note.inputValue()};
    await open(secondTitle);
    report.second_on_return = await note.inputValue();
    await open(firstTitle);
    await note.fill(firstNote);
    const snapshot = () => page.locator('#followup-area').screenshot({path:path.join(out,'after-response.png')});
    const url = new URL('/api/tasks/'+first,base).href;
    let received,release;
    const accepted = new Promise(resolve=>{received=resolve;});
    const hold = new Promise(resolve=>{release=resolve;});
    await page.route(url+'/actions',async route=>{
      const response=await route.fetch();
      assert.equal(response.status(),202);
      received();
      await hold;
      await route.fulfill({response});
    },{times:1});
    await page.locator('#revise').click();
    await accepted;
    const later='还有一本书在小林那里，见面时带上';
    await note.fill(later);
    release();
    await page.waitForFunction(()=>document.querySelector('#card-version').textContent==='第 2 版' &&
      document.querySelector('#task-status').textContent==='等待你确认');
    report.typed_while_saving = await note.inputValue();
    const saved = (await (await page.request.get(url)).json()).task;
    report.saved_messages = saved.inputs.filter(input=>input.kind==='revise').map(input=>input.message);
    if (out) {
      await snapshot();
      await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    }
    assert.equal(report.first_on_return,firstNote);
    assert.equal(report.second_on_return,secondNote);
    assert.equal(report.typed_while_saving,later);
    assert.deepEqual(report.saved_messages,[firstNote]);
    const response = await page.request.post(url+'/actions',{data:{event_id:randomUUID(),kind:'confirm',
      expected_revision:saved.revision,card_version:saved.card_version}});
    assert.equal(response.status(),202);
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    const drafts = page.locator('#card-change-notice');
    await drafts.getByText(later,{exact:true}).waitFor();
    assert.ok(await drafts.getByRole('button',{name:'复制草稿'}).isVisible());
    if(out) await drafts.screenshot({path:path.join(out,'closed-task.png')});
    await open(secondTitle);
    assert.equal(await note.inputValue(),secondNote);
    await page.locator('#revise').click();
    await page.waitForFunction(()=>document.querySelector('#card-version').textContent==='第 2 版');
    assert.equal(await note.inputValue(),'');
    Object.assign(report,{unsent_note_after_remote_confirmation:true,acknowledged_note_cleared:true});
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log('Each task kept its own note. Acknowledging an earlier save preserved later typing.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
