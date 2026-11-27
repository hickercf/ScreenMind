/* Start an information work while a calendar work is still open. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL), out = process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['localhost','127.0.0.1'].includes(base.hostname));
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#schedule-demo').click();
    await page.locator('#notice').filter({hasText:'日程样例已载入'}).waitFor();
    const current = page.locator('#work-select'), original = await current.inputValue();
    const name = page.locator('#work-name');
    await name.fill('整理借阅材料');
    const purpose = page.getByLabel('新事项类型',{exact:true});
    const hasChoice = await purpose.isVisible();
    if (hasChoice) await purpose.selectOption('information');
    const pickedWhileCreating = await current.inputValue();
    const checked = await page.locator('#screen-list input:checked').count();
    if (out) await page.locator('.work-scope').screenshot({path:path.join(out,'form.png')});
    await page.locator('#create-work').click();
    await page.locator('#memory-title').filter({hasText:'整理借阅材料'}).waitFor();
    const chosen = await current.inputValue();
    const work = async id => (await (await page.request.get(new URL('/api/works/'+id,base).href)).json()).work;
    const report = {has_new_purpose:hasChoice,kept_current:pickedWhileCreating===original,kept_checked:checked,
      old_purpose:(await work(original)).subagent_id,saved_purpose:(await work(chosen)).subagent_id};
    if (out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    assert.equal(report.saved_purpose,'information');
    assert.equal(report.old_purpose,'schedule');
    assert.equal(report.has_new_purpose,true);
    assert.ok(report.kept_current && checked>0);
    await page.locator('#work-name').fill('周六的见面时间');
    await purpose.selectOption('schedule');
    async function selectNote(title) {
      const result = await page.request.post(new URL('/api/screens',base).href,{data:{
        event_id:randomUUID(),session_id:randomUUID(),app_package:'local.reading',app_name:'读书笔记',page_title:title,
        captured_at:'2026-11-20T08:00:00+08:00',blocks:[{id:'note',kind:'paragraph',text:'请带上借书证。'}]}});
      assert.equal(result.status(),201);
      await page.getByRole('checkbox',{name:'选择 '+title}).check();
    }
    await selectNote('核对借阅材料');
    await page.locator('#instruction').fill('整理借阅材料清单');
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const tid = await page.locator('#task-view').getAttribute('data-task-id');
    const task = (await (await page.request.get(new URL('/api/tasks/'+tid,base).href)).json()).task;
    assert.equal(task.subagent_id,'information');
    assert.equal(task.work_id,chosen);
    assert.equal(await name.inputValue(),'周六的见面时间');
    await page.locator('#create-work').click();
    await page.locator('#memory-title').filter({hasText:'周六的见面时间'}).waitFor();
    assert.equal((await work(await current.inputValue())).subagent_id,'schedule');
    await current.selectOption('');
    assert.equal(await page.locator('#agent-select').inputValue(),'');
    assert.equal(await page.locator('#agent-select').isEnabled(),true);
    await selectNote('下次读书会的安排');
    await page.locator('#instruction').fill('帮我更新日程');
    await page.locator('#create-submit').click();
    const feedback=page.locator('#task-create-feedback');
    await feedback.filter({hasText:'请先选择或创建一个“日程安排”事项'}).waitFor();
    assert.doesNotMatch(await feedback.innerText(),/SubAgent|work_id|422/);
    if(out) await feedback.screenshot({path:path.join(out,'guidance.png')});
    await page.setViewportSize({width:320,height:740});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    if(out) await page.locator('.work-scope').screenshot({path:path.join(out,'small.png')});
    await page.setViewportSize({width:1360,height:850});
    if(out) await page.locator('.work-scope').screenshot({path:path.join(out,'desktop.png')});
    Object.assign(report,{new_schedule:true,current_task_purpose:task.subagent_id,standalone_choice_restored:true,
      guidance:await feedback.innerText(),widths:[320,390,1360]});
    if (out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log('Created calendar and information works from each other without changing the open work or independent-task setting.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
