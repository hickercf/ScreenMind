/* Real browser + HTTP service. All materials synthetic; no real calendar account. */
const {chromium}=require('playwright');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  const context=await browser.newContext({viewport:{width:1440,height:1050},locale:'zh-CN',timezoneId:'Asia/Taipei',acceptDownloads:true});
  const page=await context.newPage(), errors=[];
  const folder=process.env.SCREENMIND_QA_OUTPUT||'artifacts';
  fs.mkdirSync(folder,{recursive:true});
  const artifact=name=>path.join(folder,name);
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(process.env.SCREENMIND_URL||'http://127.0.0.1:8767');
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#schedule-demo').click();
    await page.locator('#notice').filter({hasText:'日程样例已载入'}).waitFor();
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    assert.equal(await page.locator('#card-fields .field-value').count(),2);
    await page.locator('#confirm').click();
    await page.locator('#memory-version').filter({hasText:'第 1 版'}).waitFor();
    await page.locator('#calendar-item').selectOption('defense');
    assert.equal(await page.locator('#calendar-end').inputValue(),'2026-12-10T18:30');
    await page.locator('#calendar-end').fill('2026-12-10T17:30');
    await page.locator('#calendar-preview').click();
    await page.locator('#notice').filter({hasText:'结束时间要晚于开始时间'}).waitFor();
    assert.equal(await page.locator('#calendar-end').inputValue(),'2026-12-10T17:30');
    await page.locator('#calendar-end').fill('2026-12-10T18:30');
    await page.locator('#calendar-preview').click();
    await page.locator('#action-preview').filter({hasText:'答辩'}).waitFor();
    await page.locator('#action-result').filter({hasText:'等待你确认'}).waitFor();
    const preview = await page.locator('#action-preview').innerText();
    assert.match(preview,/2026年12月10日 18:00/);
    assert.match(preview,/2026年12月10日 18:30/);
    assert.match(preview,/可下载的日历文件/);
    assert.match(await page.locator('#screen-list').innerText(),/提交材料/);
    assert.equal(await page.locator('#task-instruction').innerText(),'导出日程 · 答辩');
    assert.doesNotMatch(await page.locator('body').innerText(),/schedule_changes|calendar_target|task_[a-f0-9]{8}|interaction\.published|\bproposed\b/);
    const contrast=await page.locator('#confirm').evaluate(button=>{
      const luminance=rgb=>{
        const channels=rgb.match(/[\d.]+/g).slice(0,3).map(Number).map(n=>n/255)
          .map(n=>n<=.04045?n/12.92:((n+.055)/1.055)**2.4);
        return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;
      };
      const bg=getComputedStyle(button).backgroundColor,fg=getComputedStyle(button.querySelector('[data-button-label]')).color;
      const values=[luminance(bg),luminance(fg)].sort((a,b)=>b-a);
      return {bg,fg,ratio:(values[0]+.05)/(values[1]+.05)};
    });
    assert.ok(contrast.ratio>=4.5,JSON.stringify(contrast));
    await page.setViewportSize({width:390,height:844});
    await page.locator('#action-preview').scrollIntoViewIfNeeded();
    await page.screenshot({path:artifact('preview.png')});
    assert.equal(await page.locator('#action-download').isVisible(),false);
    await page.locator('#confirm').click();
    await page.locator('#action-result').filter({hasText:'日历文件已生成'}).waitFor();
    assert.doesNotMatch(await page.locator('body').innerText(),/download_url|sha256|\bsucceeded\b|action_[a-f0-9]{8}/);
    await page.locator('#action-download').scrollIntoViewIfNeeded();
    await page.screenshot({path:artifact('ready.png')});
    const downloadEvent=page.waitForEvent('download');
    await page.locator('#action-download').click();
    const download=await downloadEvent;
    assert.equal(download.suggestedFilename(),'ScreenMind-答辩.ics');
    const file=artifact('browser-calendar.ics');await download.saveAs(file);
    assert.match(fs.readFileSync(file,'utf8'),/SUMMARY:答辩/);
    assert.match(fs.readFileSync(file,'utf8'),/DTSTART:20261210T100000Z/);
    assert.match(fs.readFileSync(file,'utf8'),/DTEND:20261210T103000Z/);
    await page.setViewportSize({width:1440,height:1050});
    await page.screenshot({path:artifact('phase-two-desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
    await page.screenshot({path:artifact('phase-two-mobile.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    const result={synthetic:true,checks:['schedule demo','confirmed state v1','action preview','explicit authorization','real ICS download','mobile overflow','no browser errors'],calendar_imported:false};
    fs.writeFileSync(artifact('phase-two-browser.json'),JSON.stringify({...result,preview,contrast,filename:download.suggestedFilename()},null,2));console.log(JSON.stringify(result));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
