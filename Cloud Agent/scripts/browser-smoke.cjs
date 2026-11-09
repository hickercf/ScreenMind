/* Run with Playwright installed/resolvable via NODE_PATH. Uses an isolated headless profile. */
const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  fs.mkdirSync('artifacts', {recursive: true});
  const options = {headless: true};
  if (process.env.SCREENMIND_BROWSER) options.executablePath = process.env.SCREENMIND_BROWSER;
  const browser = await chromium.launch(options);
  const context = await browser.newContext({viewport: {width: 1440, height: 1050}, locale: 'zh-CN'});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  try {
    await page.goto(process.env.SCREENMIND_URL || 'http://127.0.0.1:8765');
    await page.locator('#mode-label').filter({hasText: '模拟模型'}).waitFor();
    await page.locator('#new-task').click();
    await page.screenshot({path:'artifacts/console-empty.png', fullPage:true});
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('.screen-top input:checked').length===2);
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    assert.equal(await page.locator('#card-fields textarea').count(), 5);
    await page.locator('#card-fields textarea').first().fill('校内预审时间：周四 17:00（用户修订）');
    await page.locator('#followup').fill('保留我修改的时间，再整理一版。');
    await page.locator('#revise').click();
    await page.locator('#card-version').filter({hasText:'CARD v2'}).waitFor();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    assert.equal(await page.locator('#card-fields textarea').first().inputValue(), '校内预审时间：周四 17:00（用户修订）');
    assert.equal(await page.locator('.user-mark').count(), 1);
    await page.locator('#card-fields summary').first().click();
    await page.evaluate(()=>window.scrollTo(0,0));
    await page.screenshot({path:'artifacts/console-desktop.png', fullPage:true});
    const tid = await page.locator('#task-id').innerText();
    // Confirm dirty input is not silently lost.
    await page.locator('#followup').fill('未保存内容');
    await page.locator('#confirm').click();
    await page.locator('#notice').filter({hasText:'未保存'}).waitFor();
    await page.locator('#followup').fill('');
    await page.locator('#confirm').click();
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    await page.reload();
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    assert.equal(await page.locator('#task-id').innerText(), tid);
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:'artifacts/console-mobile.png',fullPage:true});
    const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
    assert.equal(overflow,false,'Mobile layout must not overflow horizontally');
    assert.deepEqual(errors,[]);
    const result={passed:true,task_id:tid,checks:['create','tool result','edit and continue','user correction retained','dirty confirm blocked','confirm','reload persistence','390px responsive','no page errors'],screenshots:['console-empty.png','console-desktop.png','console-mobile.png']};
    fs.writeFileSync('artifacts/browser-report.json',JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
