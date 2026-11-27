/* Read and confirm the same information card as a sighted reader or a form reader. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
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
    await page.locator('#work-name').fill('材料准备');
    await page.locator('#create-work').click();
    await page.locator('#work-feedback').filter({hasText:'事项已创建'}).waitFor();
    for (const checkbox of await page.locator('#screen-list input').all()) await checkbox.check();
    await page.locator('#create-submit').click();
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const fields = page.locator('#card-fields');
    const labels = await fields.locator('.field-label > span:first-child').allTextContents();
    const expected = ['WPS · 校内材料提交截止时间','WPS · 正文摘录','WPS · 要点','微信 · 老师','微信 · 我'];
    assert.deepEqual(labels.slice().sort(),expected.slice().sort());
    const deadline = fields.getByRole('textbox',{name:expected[0],exact:true});
    assert.match(await deadline.inputValue(),/2026年11月13日/);
    const sources = deadline.locator('..').locator('details');
    await sources.locator('summary').click();
    assert.match(await sources.innerText(),/报名通知/);
    if(out) await fields.screenshot({path:path.join(out,'card.png')});
    await page.locator('#confirm').click();
    await page.locator('#task-status').filter({hasText:'已完成'}).waitFor();
    const memory = page.locator('#memory-details');
    await page.locator('#memory-panel details > summary').click();
    await memory.getByText(expected[0],{exact:true}).waitFor();
    assert.deepEqual((await memory.locator('dt').allTextContents()).sort(),expected.slice().sort());
    if(out) await page.locator('#memory-panel').screenshot({path:path.join(out,'confirmed.png')});
    await page.reload();
    await fields.getByRole('textbox',{name:expected[0],exact:true}).waitFor();
    assert.deepEqual((await fields.locator('.field-label > span:first-child').allTextContents()).sort(),expected.slice().sort());
    if(out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify({labels,accessible_headings:true,
      source_shown:true,confirmed_and_reopened:true},null,2));
    console.log('The card, its form labels and confirmed memory all show readable headings after reopening.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
