/* Use the real task history with a keyboard and a narrow phone viewport. */
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL), out = process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['localhost','127.0.0.1'].includes(base.hostname));
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    async function post(resource, data) {
      const response = await page.request.post(new URL('/api/'+resource, base).href,{data});
      assert.ok(response.ok(),await response.text());
      return response.json();
    }
    const {screen} = await post('screens',{event_id:randomUUID(),session_id:randomUUID(),
      app_package:'local.reading',app_name:'读书笔记',page_title:'周末借阅安排',captured_at:new Date().toISOString(),
      blocks:[{id:'note',kind:'paragraph',text:'周六在图书馆见面，记得带上借书证。'}]});
    const create = instruction => post('tasks',{event_id:randomUUID(),subagent_id:'information',instruction,screen_ids:[screen.id]});
    const first = '整理图书馆的借阅材料', second = '核对周六见面的安排';
    const {task} = await create(first);
    await create(second);
    await page.goto(base.href);
    const history = title => page.locator('#task-list button').filter({hasText:title});
    const heading = page.locator('#task-title');
    await history(second).click();
    await page.locator('#task-instruction').filter({hasText:second}).waitFor();
    await history(first).focus();
    await page.keyboard.press('Enter');
    await page.locator('#task-instruction').filter({hasText:first}).waitFor();
    const view = locator => locator.evaluate(h=>{
      const rect=h.getBoundingClientRect();
      return {top:Math.round(rect.top),height:innerHeight,visible:rect.top>=0&&rect.bottom<=innerHeight,focus:document.activeElement===h};
    });
    const report = {phone:await view(heading)};
    if(out) await page.screenshot({path:path.join(out,'phone.png')});
    await page.locator('#new-task').click();
    const compose = page.getByRole('heading',{name:'新建任务',exact:true});
    await compose.waitFor();
    report.new_task = await view(compose);
    if(out) await page.screenshot({path:path.join(out,'compose.png')});
    await history(first).focus();
    await create('从另一台设备补充借阅说明');
    await history('从另一台设备补充借阅说明').waitFor();
    report.kept_keyboard_focus = await history(first).evaluate(b=>b===document.activeElement);
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    if (process.env.SCREENMIND_EXPECT_OLD_NAVIGATION) {
      assert.equal(report.phone.visible,false);
      assert.equal(report.new_task.visible,false);
      assert.equal(report.kept_keyboard_focus,false);
      console.log(JSON.stringify(report));return;
    }
    assert.ok(report.phone.visible&&report.phone.focus);
    assert.ok(report.new_task.visible&&report.new_task.focus);
    assert.ok(report.kept_keyboard_focus);
    await page.setViewportSize({width:320,height:740});
    await history(first).click();
    await page.waitForFunction(()=>document.getElementById('task-title')===document.activeElement);
    report.small = await view(heading);
    assert.ok(report.small.visible);
    const version = page.locator('#card-version');
    await version.filter({hasText:'第 1 版'}).waitFor();
    assert.equal(await page.locator('#card-title').innerText(),'线索摘录');
    assert.equal(await page.locator('#card-summary').innerText(),'摘录自 1 份线索。');
    const activity = page.locator('#event-list');
    await activity.getByText('开始处理',{exact:true}).waitFor();
    await activity.getByText('已生成结果',{exact:true}).waitFor();
    report.progress_labels = await activity.locator('strong').allTextContents();
    assert.deepEqual(report.progress_labels,['开始处理','已生成结果']);
    report.unclipped_version = await version.evaluate(e=>e.parentElement.scrollWidth<=e.parentElement.clientWidth+1);
    assert.ok(report.unclipped_version);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    if(out) await page.screenshot({path:path.join(out,'small.png')});
    await page.setViewportSize({width:1360,height:850});
    await history(second).click();
    await page.locator('#task-instruction').filter({hasText:second}).waitFor();
    report.desktop = await view(heading);
    assert.ok(report.desktop.visible&&report.desktop.focus);
    if(out) await page.screenshot({path:path.join(out,'desktop.png')});
    let received, release;
    const asked = new Promise(done=>{received=done;});
    const held = new Promise(done=>{release=done;});
    await page.route('**/api/tasks/'+task.id,async route=>{
      const response = await route.fetch();
      received();await held;await route.fulfill({response});
    },{times:1});
    await history(first).click();await asked;
    const name = page.locator('#work-name');
    await name.fill('我在等候时输入的事项');release();
    await page.locator('#task-instruction').filter({hasText:first}).waitFor();
    report.continued_typing = await name.evaluate(e=>e===document.activeElement&&e.value==='我在等候时输入的事项');
    assert.ok(report.continued_typing);
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
