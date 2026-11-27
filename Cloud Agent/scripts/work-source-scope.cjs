/* Keep the reader's checked sources within the work in which they selected them. */
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL), out = process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['127.0.0.1','localhost'].includes(base.hostname));
  const browser = await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    async function post(resource,data) {
      const response=await page.request.post(new URL('/api/'+resource,base).href,{data});
      assert.ok(response.ok(),await response.text());return response.json();
    }
    const work = async title => (await post('works',{event_id:randomUUID(),title,subagent_id:'information'})).work.id;
    const note = async (title,text) => (await post('screens',{event_id:randomUUID(),session_id:randomUUID(),
      app_package:'local.reading',app_name:'读书笔记',page_title:title,captured_at:new Date().toISOString(),
      blocks:[{id:'line',kind:'paragraph',text}]})).screen.id;
    const task = async (wid,sid,title) => (await post('tasks',{event_id:randomUUID(),work_id:wid,
      instruction:title,screen_ids:[sid]})).task.id;
    const reading=await work('读书会'),walk=await work('公园散步');
    const library=await note('读书会通知','带上借书证。'), park=await note('公园集合','周六在南门见面。');
    const extraTitle='只在读书会使用的还书清单';
    const extra=await note(extraTitle,'归还上次借来的两本书。');
    const first='整理读书会安排', second='核对借阅材料', third='整理公园集合说明';
    const readingTask=await task(reading,library,first);
    await task(reading,library,second);
    const walkTask=await task(walk,park,third);
    const getTask=async id=>(await (await page.request.get(new URL('/api/tasks/'+id,base).href)).json()).task;
    await page.goto(base.href);
    async function open(title){
      await page.locator('#task-list button').filter({hasText:title}).click();
      await page.locator('#task-instruction').filter({hasText:title}).waitFor();
      await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    }
    const checkbox=page.getByRole('checkbox',{name:'选择 '+extraTitle});
    const before=!!process.env.SCREENMIND_EXPECT_OLD_NAVIGATION;
    await open(first);await checkbox.check();await open(second);
    const report={same_work_kept_choice:await checkbox.isChecked()};
    let releaseWork;
    if(!before){
      await page.locator('#memory-title').filter({hasText:'读书会'}).waitFor();
      const held=new Promise(resolve=>{releaseWork=resolve;});
      await page.route('**/api/works/'+walk,async route=>{await held;await route.continue();});
    }
    await open(third);
    report.new_work_inherited_choice=await checkbox.isChecked();
    if(!before){
      report.previous_work_hidden_while_loading=await page.locator('#memory-panel').isHidden();
      releaseWork();
      await page.locator('#memory-title').filter({hasText:'公园散步'}).waitFor();
      assert.equal(report.previous_work_hidden_while_loading,true);
    }
    if(out) await page.locator('#screen-list').screenshot({path:path.join(out,'sources.png')});
    async function save(message){
      await page.locator('#attach-screens').check();
      await page.locator('#followup').fill(message);await page.locator('#revise').click();
      await page.waitForFunction(()=>document.getElementById('task-revision').textContent==='第 2 次修订'&&
        document.getElementById('task-status').textContent==='等待你确认');
    }
    await save('请补充公园南门的集合说明。');
    const walkSaved=await getTask(walkTask);
    report.other_work_received_reading_note=walkSaved.screen_ids.includes(extra);
    report.other_work_source_count=walkSaved.screen_ids.length;
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    assert.ok(report.same_work_kept_choice);
    if(before){
      assert.ok(report.new_work_inherited_choice && report.other_work_received_reading_note);
      console.log(JSON.stringify(report));return;
    }
    assert.equal(report.new_work_inherited_choice,false);
    assert.deepEqual(walkSaved.screen_ids,[park]);
    await open(first);assert.equal(await checkbox.isChecked(),false);
    await checkbox.check();await save('请加入这次需要归还的书。');
    assert.deepEqual(new Set((await getTask(readingTask)).screen_ids),new Set([library,extra]));
    assert.deepEqual((await getTask(walkTask)).screen_ids,[park]);
    report.explicit_selection_reached_reading_work=true;
    if(out) await page.locator('#card-view').screenshot({path:path.join(out,'reading.png')});
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
