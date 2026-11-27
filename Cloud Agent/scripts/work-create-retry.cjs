/* The isolated service accepts a new work before the browser loses the response. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost', '127.0.0.1'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless: true, executablePath: process.env.SCREENMIND_BROWSER});
  try {
    const page = await browser.newPage({viewport: {width: 390, height: 844}, locale: 'zh-CN'});
    const receipts = [];
    let delayedReply;
    function delayNextReply() {
      let accepted, release;
      const arrived = new Promise(resolve => { accepted = resolve; });
      const wait = new Promise(resolve => { release = resolve; });
      delayedReply = {accepted, wait};
      return {arrived, release};
    }
    await page.route('**/api/works', async route => {
      if (route.request().method() !== 'POST') return route.continue();
      const response = await route.fetch();
      const body = await response.json();
      receipts.push({request: route.request().postDataJSON().event_id, work: body.work.id, replayed: body.replayed});
      assert.equal(response.status(), 201);
      if (receipts.length === 1) return route.abort('connectionreset');
      if (delayedReply) {
        const {accepted, wait} = delayedReply;
        delayedReply = undefined;
        accepted();
        await wait;
      }
      await route.fulfill({response});
    });
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    const title = '周五图书馆读书会';
    const input = page.locator('#work-name'), create = page.locator('#create-work');
    const feedback = page.locator('#work-feedback:visible, #notice:visible');
    await input.fill(title);
    await create.click();
    await feedback.filter({hasText:/连接|网络|重试/}).waitFor();
    assert.equal(await input.inputValue(), title);
    if (out) await page.screenshot({path:path.join(out,'interrupted.png')});
    await create.click();
    await feedback.filter({hasText:'事项已创建'}).waitFor();
    const readWorks = async () => (await (await page.request.get(new URL('/api/works',base).href)).json()).works;
    const works = (await readWorks()).filter(work => work.title === title);
    const report = {accepted: receipts.slice(), saved_works: works.length,
      unique_work_ids: new Set(receipts.map(r => r.work)).size, title_after_retry: await input.inputValue()};
    if (out) await fs.writeFile(path.join(out,'receipts.json'), JSON.stringify(report,null,2));
    assert.deepEqual(receipts.map(r => r.replayed), [false,true]);
    assert.equal(receipts[0].request,receipts[1].request);
    assert.equal(works.length,1);
    assert.equal(await page.locator('#work-select').inputValue(),works[0].id);
    assert.equal(await input.inputValue(),'');
    if (out) await page.screenshot({path:path.join(out,'recovered.png')});
    // Entering the same name again after an acknowledged success is a separate user action.
    await input.fill(title);
    await create.click();
    await page.waitForFunction(() => document.querySelector('#work-name').value === '');
    assert.equal((await readWorks()).filter(w => w.title === title).length,2);
    assert.notEqual(receipts[1].request,receipts[2].request);
    const selectedBeforeEdit = await page.locator('#work-select').inputValue();
    const editing = delayNextReply();
    await input.fill('周一借书');
    await create.click();
    await editing.arrived;
    await input.fill('周二还书');
    editing.release();
    await page.waitForFunction(() => !document.querySelector('#create-work').disabled);
    assert.equal(await input.inputValue(),'周二还书');
    assert.equal(await page.locator('#work-select').inputValue(),selectedBeforeEdit);
    const navigation = delayNextReply();
    await create.click();
    await navigation.arrived;
    await page.locator('#work-select').selectOption(works[0].id);
    navigation.release();
    await page.waitForFunction(() => !document.querySelector('#create-work').disabled);
    assert.equal(await page.locator('#work-select').inputValue(),works[0].id);
    const finalWorks = await readWorks();
    assert.equal(finalWorks.length,4);
    Object.assign(report, {deliberate_same_title:2, draft_retained_after_reply:true,
      selection_retained_after_navigation:true, total_works:finalWorks.length});
    if (out) await fs.writeFile(path.join(out,'receipts.json'), JSON.stringify(report,null,2));
    console.log('Recovered one work, verified a separate repeat, and kept editing and navigation intact.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
