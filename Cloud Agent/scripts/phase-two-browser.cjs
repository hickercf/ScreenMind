/* Real browser + HTTP service. All materials synthetic; no real calendar account. */
const {chromium}=require('playwright');
const fs=require('node:fs');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.SCREENMIND_BROWSER});
  const context=await browser.newContext({viewport:{width:1440,height:1050},locale:'zh-CN',timezoneId:'Asia/Taipei',acceptDownloads:true});
  const page=await context.newPage(), errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(process.env.SCREENMIND_URL||'http://127.0.0.1:8767');
    await page.locator('#mode-label').filter({hasText:'模拟模型'}).waitFor();
    await page.locator('#schedule-demo').click();
    await page.locator('#notice').filter({hasText:'日程样例已载入'}).waitFor();
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    assert.equal(await page.locator('#card-fields textarea').count(),2);
    await page.locator('#confirm').click();
    await page.locator('#memory-version').filter({hasText:'v1'}).waitFor();
    await page.locator('#calendar-item').selectOption('defense');
    await page.locator('#calendar-end').fill('2026-12-10T18:30');
    await page.locator('#calendar-preview').click();
    await page.locator('#action-preview').filter({hasText:'答辩'}).waitFor();
    await page.locator('#action-result').filter({hasText:'proposed'}).waitFor();
    assert.equal(await page.locator('#action-download').isVisible(),false);
    await page.locator('#confirm').click();
    await page.locator('#action-result').filter({hasText:'succeeded'}).waitFor();
    const downloadEvent=page.waitForEvent('download');
    await page.locator('#action-download').click();
    const download=await downloadEvent;
    const file='artifacts/browser-calendar.ics';await download.saveAs(file);
    assert.match(fs.readFileSync(file,'utf8'),/SUMMARY:答辩/);
    await page.screenshot({path:'artifacts/phase-two-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
    await page.screenshot({path:'artifacts/phase-two-mobile.png',fullPage:true});
    assert.deepEqual(errors,[]);
    const result={synthetic:true,checks:['schedule demo','confirmed state v1','action preview','explicit authorization','real ICS download','mobile overflow','no browser errors'],calendar_imported:false};
    fs.writeFileSync('artifacts/phase-two-browser.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
