/* A second local client adds a record while the reader has the oldest source checked. */
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const {randomUUID}=require('node:crypto');
const {chromium}=require('playwright');

(async()=>{
  const base=new URL(process.env.SCREENMIND_URL),out=process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['127.0.0.1','localhost'].includes(base.hostname));
  const before=!!process.env.SCREENMIND_EXPECT_OLD_NAVIGATION;
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN'});
    const api=new URL('/api/screens',base).href, taskApi=new URL('/api/tasks',base).href;
    const oldestTitle='第一天的借书约定',recentTitle='刚刚收到的还书通知';
    async function add(title,body){
      const response=await page.request.post(api,{data:{event_id:randomUUID(),session_id:'reading-journal',
        app_package:'local.reading',app_name:'读书笔记',page_title:title,captured_at:new Date().toISOString(),
        blocks:[{id:'book:note',kind:'paragraph',text:body}]}});
      assert.equal(response.status(),201);return (await response.json()).screen.id;
    }
    const oldest=await add(oldestTitle,'到南门借那本散文集。');
    for(let i=2;i<=100;i++)await add('第'+i+'次读书笔记','本次读到了第'+i+'页。');
    await page.goto(base.href);
    const panel=page.locator('.context-panel'),list=page.locator('#screen-list');
    const check=page.getByRole('checkbox',{name:'选择 '+oldestTitle});
    const more=page.locator('#older-screens');
    await list.locator('article').first().waitFor();
    const report={initial_count:Number(await page.locator('#screen-count').innerText()),
      initial_displayed:await list.locator('article').count()};
    let pages=0;
    while(!await check.count() && await more.isVisible()){
      assert.ok(++pages<8);
      const oldCount=await list.locator('article').count();
      await more.click();await page.waitForFunction(n=>document.querySelectorAll('#screen-list article').length>n,oldCount);
    }
    await check.check();
    await add(recentTitle,'下周一之前归还。');
    await page.getByRole('checkbox',{name:'选择 '+recentTitle}).waitFor();
    report.oldest_choice_kept=!!await check.count() && await check.isChecked();
    report.count_after_addition=Number(await page.locator('#screen-count').innerText());
    report.older_pages_opened=pages;
    if(out) await panel.screenshot({path:path.join(out,'library.png')});
    await page.locator('#create-submit').click();
    if(before){
      await page.locator('#task-create-feedback:visible').waitFor();
      report.submission_message=await page.locator('#task-create-feedback').innerText();
      report.saved_tasks=(await (await page.request.get(taskApi)).json()).tasks.length;
      assert.equal(report.oldest_choice_kept,false);
      assert.equal(report.count_after_addition,100);
      assert.equal(report.saved_tasks,0);
    }else{
      assert.ok(report.older_pages_opened>0 && report.oldest_choice_kept);
      assert.equal(report.count_after_addition,101);
      await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
      const [saved]=(await (await page.request.get(taskApi)).json()).tasks;
      const detail=(await (await page.request.get(taskApi+'/'+saved.id)).json()).task;
      assert.deepEqual(detail.screen_ids,[oldest]);
      report.saved_sources=detail.screen_ids.length;
      await page.locator('#confirm').click();
      await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
      await page.reload();
      await page.locator('#task-list button').first().click();
      await page.locator('#card-fields details summary').first().click();
      const source=page.locator('#card-fields [data-source-ref]').first();
      await source.filter({hasText:'到南门借那本散文集。'}).waitFor();
      assert.match(await source.innerText(),/第一天的借书约定/);
      report.original_source_readable_after_reopening=true;
      if(out) await page.locator('#card-view').screenshot({path:path.join(out,'reopened.png')});
      const withdrawal=await page.request.delete(api+'/'+oldest);
      assert.equal(withdrawal.status(),200);
      await source.filter({hasText:'这条来源目前无法查看。'}).waitFor({state:'attached'});
      report.withdrawn_source_cleared=true;
    }
    if(out)await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
