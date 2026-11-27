/* A plain note prompts a real clarification. Answer and confirm through the normal UI. */
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const {randomUUID}=require('node:crypto');
const {chromium}=require('playwright');

(async()=>{
  const base=new URL(process.env.SCREENMIND_URL),out=process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['localhost','127.0.0.1'].includes(base.hostname));
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN',timezoneId:'Asia/Taipei'});
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.getByLabel('新事项类型',{exact:true}).selectOption('schedule');
    await page.locator('#work-name').fill('图书馆读书会');
    await page.locator('#create-work').click();
    await page.locator('#memory-title').filter({hasText:'图书馆读书会'}).waitFor();
    const wid=await page.locator('#work-select').inputValue();
    const saved=await page.request.post(new URL('/api/screens',base).href,{data:{event_id:randomUUID(),session_id:wid,
      app_package:'local.reader',app_name:'读书笔记',page_title:'下次见面的安排',captured_at:'2026-11-20T09:10:00+08:00',
      blocks:[{id:'place',kind:'paragraph',text:'这次在图书馆碰面，时间还没定。'}]}});
    assert.equal(saved.status(),201);
    await page.getByRole('checkbox',{name:'选择 下次见面的安排'}).check();
    await page.locator('#instruction').fill('帮我记下读书会的时间');
    await page.locator('#create-submit').click();
    const question=page.locator('#question-view');
    await question.waitFor();
    const report={initial_question:await question.innerText()};
    if(out)await question.screenshot({path:path.join(out,'question.png')});
    const id=await page.locator('#task-view').getAttribute('data-task-id');
    const url=new URL('/api/tasks/'+id,base).href;
    let accepted=0;
    await page.route(url+'/responses',async route=>{
      const response=await route.fetch();
      assert.equal(response.status(),202);
      accepted++;
      if(accepted===1)return route.abort('connectionreset');
      await route.fulfill({response});
    });
    const answer='旧书交换会定在2027年2月8日 下午2点半（台北时间）';
    await page.locator('#followup').fill(answer);
    await page.locator('#revise').click();
    await page.waitForFunction(()=>document.querySelector('#task-revision').textContent==='第 2 次修订' &&
      document.querySelector('#task-status').textContent==='等待你确认');
    const task=(await(await page.request.get(url)).json()).task;
    report.after_answer=task.interaction.kind;
    report.answer_in_form=await page.locator('#followup').inputValue();
    const work=async()=>(await(await page.request.get(new URL('/api/works/'+wid,base).href)).json()).work;
    report.confirmed_before_click=(await work()).memory_version;
    report.saved_answers=task.inputs.filter(input=>input.message===answer).length;
    const snapshot=async()=>{if(out)await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));};
    await snapshot();
    assert.doesNotMatch(report.initial_question,/JSON|schedule_changes/i);
    assert.equal(report.after_answer,'state_proposal');
    assert.equal(report.answer_in_form,'');
    assert.equal(report.saved_answers,1);
    assert.equal(report.confirmed_before_click,0);
    const field=page.getByRole('group',{name:'旧书交换会'}).locator('.field-value');
    assert.match(await field.innerText(),/2月8日.*14:30/);
    assert.doesNotMatch(await field.innerText(),/pending|T14:30/);
    if(out)await page.locator('#card-view').screenshot({path:path.join(out,'candidate.png')});
    await page.locator('#confirm').click();
    await page.locator('#memory-version').filter({hasText:'第 1 版'}).waitFor();
    const items=Object.values((await work()).state.items);
    assert.equal(items.length,1);
    assert.equal(items[0].title,'旧书交换会');
    assert.equal(items[0].at,'2027-02-08T14:30:00+08:00');
    assert.ok(items[0].source_refs.some(ref=>ref.startsWith('input:'+id+':')));
    report.confirmed={title:items[0].title,at:items[0].at,from_answer:true};
    await snapshot();
    console.log('A plain dated answer yielded a proposal. Its source, offset and explicit confirmation were preserved.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
