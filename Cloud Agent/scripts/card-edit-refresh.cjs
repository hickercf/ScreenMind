/* Two clients revise an actual card while the first reader is typing. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost','127.0.0.1'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('#screen-list input:checked').length===2);
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const tid=await page.locator('#task-view').getAttribute('data-task-id');
    const url=new URL('/api/tasks/'+tid,base).href;
    const read=async()=>(await (await page.request.get(url)).json()).task;
    const first=await read();
    const deadline=first.card.fields.find(f=>f.label.includes('截止时间'));
    const materials=first.card.fields.find(f=>f.label.includes('正文摘录'));
    const input=f=>page.locator('#card-fields textarea[data-field-id="'+f.id+'"]');
    const local='我记下的约定是周四下午四点';
    const remote='老师后来通知改到周五上午九点';
    async function change(field,value) {
      const t=await read();
      const response=await page.request.post(url+'/actions',{data:{event_id:randomUUID(),kind:'revise',
        expected_revision:t.revision,card_version:t.card_version,edits:{[field.id]:value},message:'补充读书会安排'}});
      assert.equal(response.status(),202);
      await page.waitForFunction(version=>document.querySelector('#card-version').textContent==='第 '+version+' 版' &&
        document.querySelector('#task-status').textContent==='等待你确认',t.card_version+1);
    }
    await input(deadline).fill(local);
    await input(deadline).evaluate(el=>el.setSelectionRange(3,5));
    await change(materials,'另外带上手写的读书笔记');
    const report={draft_after_unrelated_edit:await input(deadline).inputValue(),
      other_field_updated:await input(materials).inputValue(),focus_retained:await input(deadline).evaluate(el=>el===document.activeElement),
      caret_after_refresh:await input(deadline).evaluate(el=>[el.selectionStart,el.selectionEnd])};
    await input(deadline).fill(local);
    await change(deadline,remote);
    report.draft_after_same_field_edit=await input(deadline).inputValue();
    report.conflict_visible=await page.locator('#card-change-notice').isVisible();
    if(out) {
      await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
      await page.locator('.task-panel').screenshot({path:path.join(out,'conflict.png')});
    }
    assert.equal(report.draft_after_unrelated_edit,local);
    assert.equal(report.other_field_updated,'另外带上手写的读书笔记');
    assert.ok(report.focus_retained);
    assert.deepEqual(report.caret_after_refresh,[3,5]);
    assert.equal(report.draft_after_same_field_edit,local);
    assert.ok(report.conflict_visible);
    const conflicts=page.locator('#card-change-notice');
    await conflicts.getByText(remote,{exact:true}).waitFor();
    const changedField=input(deadline).locator('..');
    const indicator=changedField.getByRole('button',{name:'另一端也改了这一栏，查看两份内容'});
    await indicator.click();
    assert.ok(await conflicts.locator('.draft-row').evaluate(row=>row===document.activeElement));
    if(out) await changedField.screenshot({path:path.join(out,'field-notice.png')});
    if(out) await conflicts.screenshot({path:path.join(out,'decision.png')});
    let posts=0;
    page.on('request',req=>{if(req.method()==='POST' && req.url()===url+'/actions')posts++;});
    await page.locator('#revise').click();
    assert.equal(posts,0);
    await conflicts.getByRole('button',{name:'保留我的修改'}).click();
    await page.locator('#revise').click();
    await page.locator('.user-mark').first().waitFor();
    await page.waitForFunction(()=>document.querySelector('#card-version').textContent==='第 4 版');
    assert.equal((await read()).card.fields.find(f=>f.id===deadline.id).value,local);
    assert.equal(posts,1);
    await input(deadline).fill('我先记周六下午');
    await change(deadline,'请大家周六早上到场');
    await conflicts.getByRole('button',{name:'使用云端内容'}).click();
    assert.equal(await input(deadline).inputValue(),'请大家周六早上到场');
    await input(materials).fill('记得替我留一本书');
    const last=await read();
    const closed=await page.request.post(url+'/actions',{data:{event_id:randomUUID(),kind:'confirm',
      expected_revision:last.revision,card_version:last.card_version}});
    assert.equal(closed.status(),202);
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    await conflicts.getByText('记得替我留一本书',{exact:true}).waitFor();
    assert.ok(await input(materials).isDisabled());
    assert.notEqual(await input(materials).inputValue(),'记得替我留一本书');
    if(out) await conflicts.screenshot({path:path.join(out,'completed-draft.png')});
    Object.assign(report,{explicit_local_choice_saved:true,explicit_remote_choice_applied:true,
      completed_card_preserved_unsent_draft:true});
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log('Independent edits merged, simultaneous edits required a choice, and an unsent draft survived remote confirmation.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
