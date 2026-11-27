/* A local record stays in the browser until the user approves the preview. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');

(async () => {
  const base=new URL(process.env.SCREENMIND_URL), out=process.env.SCREENMIND_QA_OUTPUT;
  assert.ok(['127.0.0.1','localhost'].includes(base.hostname));
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844},locale:'zh-CN',timezoneId:'Asia/Taipei'});
    const record={event_id:randomUUID(),session_id:'reading-club',app_package:'cn.example.reading',app_name:'读书笔记',
      page_title:'周五读书会',captured_at:'2026-12-10T13:40:00+08:00',blocks:[
        {id:'place',kind:'chat',speaker:'小林',text:'图书馆二楼见。',bbox:[20,30,500,95]},
        {id:'calendar',kind:'paragraph',text:JSON.stringify({schedule_changes:[{id:'talk',title:'分享第三章',at:'2026-12-11T18:00:00+08:00'}]})}]};
    const receipts=[];
    await page.route('**/api/screens',async route=>{
      if(route.request().method()!=='POST')return route.continue();
      const response=await route.fetch();
      assert.equal(response.status(),201);
      const data=await response.json();
      receipts.push({id:data.screen.id,replayed:data.replayed});
      if(receipts.length===1)return route.abort('connectionreset');
      return route.fulfill({response});
    });
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#import-toggle').click();
    const panel=page.locator('#import-panel'), field=page.locator('#screen-json'), picker=page.locator('#record-file');
    const report=async data=>{if(out)await fs.writeFile(path.join(out,'result.json'),JSON.stringify(data,null,2));};
    if(!await picker.count()){
      await field.fill(JSON.stringify(record,null,2));
      if(out)await panel.screenshot({path:path.join(out,'preview.png')});
      await report({file_picker:false,raw_record_visible:(await field.inputValue()).includes('schedule_changes'),posts:receipts.length});
      assert.fail('The workbench only offers the raw record as an editable string.');
    }
    const choose=(name,content)=>picker.setInputFiles({name,mimeType:'application/json',buffer:Buffer.from(content)});
    const preview=page.locator('#import-preview'), save=page.locator('#import-submit');
    const file=JSON.stringify({id:'scr_from_export',...record},null,2);
    await choose('周五读书会.json',file);
    await preview.getByText('周五读书会',{exact:true}).waitFor();
    assert.match(await preview.innerText(),/分享第三章，2026年12月11日 18:00（UTC\+08:00）/);
    assert.ok(!(await panel.innerText()).includes('schedule_changes'));
    assert.equal(await field.inputValue(),'');
    assert.equal(receipts.length,0);
    if(out)await panel.screenshot({path:path.join(out,'preview.png')});

    await choose('未导出完成.json','{"blocks":');
    await page.locator('#import-feedback:visible').waitFor();
    assert.equal(await preview.isVisible(),false);
    assert.equal(await save.isEnabled(),false);
    assert.equal(receipts.length,0);
    if(out)await panel.screenshot({path:path.join(out,'unreadable.png')});

    await field.evaluate((element,value)=>{
      const data=new DataTransfer(); data.setData('text/plain',value);
      element.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));
    },JSON.stringify(record));
    await preview.getByText('周五读书会',{exact:true}).waitFor();
    assert.equal(await field.inputValue(),'');
    await save.click();
    await page.locator('#import-feedback').filter({hasText:/连接|重试/}).waitFor();
    assert.equal(await preview.isVisible(),true);
    await save.click();
    await page.locator('#screen-list article').filter({hasText:'周五读书会'}).getByRole('checkbox',{checked:true}).waitFor();
    assert.equal(await panel.isVisible(),false);
    assert.deepEqual(receipts.map(r=>r.replayed),[false,true]);
    assert.equal(receipts[0].id,receipts[1].id);
    const {screens}=await (await page.request.get(new URL('/api/screens',base).href)).json();
    assert.equal(screens.length,1);
    assert.deepEqual(screens[0].blocks[0].bbox,record.blocks[0].bbox);
    assert.equal(screens[0].blocks[1].text,record.blocks[1].text);
    await page.locator('#import-toggle').click();
    assert.equal(await preview.isVisible(),false);
    await choose('待取消.json',file);
    await preview.getByText('周五读书会',{exact:true}).waitFor();
    await page.locator('#import-toggle').click();
    await page.locator('#import-toggle').click();
    assert.equal(await preview.isVisible(),false);
    assert.equal(receipts.length,2);

    const plain='请在南门等我。';
    const raw={...record,event_id:randomUUID(),app_name:'{"client_tag":"qa"}',page_title:'{"page_key":"qa"}',
      blocks:[{id:'first',kind:'chat',speaker:'{"speaker_key":"qa"}',text:plain}]};
    const added=await page.request.post(new URL('/api/screens',base).href,{data:raw});
    assert.equal(added.status(),201);
    const rawRow=page.locator('#screen-list article').filter({hasText:plain});
    await rawRow.getByRole('checkbox',{name:'选择 屏幕记录'}).waitFor();
    await rawRow.locator('summary').click();
    assert.doesNotMatch(await rawRow.innerText(),/client_tag|page_key|speaker_key/);
    await page.getByRole('checkbox',{name:'选择 周五读书会'}).uncheck();
    await rawRow.getByRole('checkbox').check();
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    await page.locator('#card-fields summary').first().click();
    const source=page.locator('#card-fields [data-source-ref]:visible').first();
    assert.match(await source.innerText(),/请在南门等我/);
    assert.doesNotMatch(await page.locator('#card-view').innerText(),/client_tag|page_key|speaker_key/);
    if(out) await page.locator('#card-view').screenshot({path:path.join(out,'readable-source.png')});
    await report({file_picker:true,raw_record_visible:false,posts_before_confirmation:0,invalid_file_discarded:true,
      paste_previewed:true,close_discards_unsaved_preview:true,selection_restored:true,stored_records:1,
      original_coordinates_and_schedule_preserved:true,structured_metadata_hidden:true,receipts});
    console.log('File and paste previews stayed local, failed reads cleared the preview, and retry saved one complete record.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
