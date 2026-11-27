/* Compare the same appointment on two devices across the date boundary. */
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');

(async () => {
  const base = new URL(process.env.SCREENMIND_URL);
  assert.ok(['localhost', '127.0.0.1'].includes(base.hostname));
  const out = process.env.SCREENMIND_QA_OUTPUT;
  const browser = await chromium.launch({headless:true, executablePath:process.env.SCREENMIND_BROWSER});
  const source = '2027-02-08T01:05:12+05:45';
  const schedule = {schedule_changes:[{id:'exchange', title:'线上交换书单', at:source}]};
  const report = {};
  try {
    let wid;
    const readers = [];
    for (const [name, zone] of [['west','America/Los_Angeles'], ['east','Asia/Taipei']]) {
      const page = await browser.newPage({viewport:{width:360,height:800},locale:'zh-CN',timezoneId:zone});
      readers.push({name,zone,page});
      await page.goto(base.href);
      await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
      if (!wid) {
        await page.getByLabel('新事项类型',{exact:true}).selectOption('schedule');
        await page.locator('#work-name').fill('线上读书小组');
        await page.locator('#create-work').click();
        await page.locator('#memory-title').filter({hasText:'线上读书小组'}).waitFor();
        wid = await page.locator('#work-select').inputValue();
        const saved = await page.request.post(new URL('/api/screens',base).href,{data:{event_id:randomUUID(),session_id:wid,
          app_package:'local.reading',app_name:'读书笔记',page_title:'书单交换邀请',captured_at:'2026-11-20T11:10:00+08:00',
          blocks:[{id:'invitation',kind:'paragraph',text:JSON.stringify(schedule)}]}});
        assert.equal(saved.status(),201);
        await page.getByRole('checkbox',{name:'选择 书单交换邀请'}).check();
        await page.locator('#instruction').fill('核对线上见面时间');
        await page.locator('#create-submit').click();
      }
      await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
      const field = page.locator('#field-exchange');
      report[name] = {zone,candidate:await field.evaluate(node => node.value ?? node.innerText)};
      if (out) await page.locator('#card-view').screenshot({path:path.join(out,name+'-card.png')});
    }
    await readers[0].page.locator('#confirm').click();
    for (const {page,name} of readers) {
      await page.locator('#memory-version').filter({hasText:'第 1 版'}).waitFor();
      await page.locator('#memory-panel details > summary').click();
      const details = page.locator('#memory-details');
      await details.getByText('线上交换书单',{exact:true}).waitFor();
      const memory = await details.innerText();
      const option = await page.locator('#calendar-item option:checked').innerText();
      const record = (await (await page.request.get(new URL('/api/works/'+wid,base).href)).json()).work.state.items.exchange;
      assert.equal(record.at,source);
      if (out) await page.locator('#memory-panel').screenshot({path:path.join(out,name+'-confirmed.png')});
      Object.assign(report[name],{memory,option,stored:record.at,
        overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)});
      await page.close();
    }
    if (out) await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    for (const [name, local] of [['west','2月7日 11:20:12'],['east','2月8日 03:20:12']]) {
      for (const value of [report[name].candidate,report[name].memory,report[name].option]) {
        assert.ok(value.includes('2月8日 01:05:12') && value.includes('UTC+05:45'),value);
        assert.ok(value.includes('本机') && value.includes(local),value);
      }
      assert.equal(report[name].overflow,false);
    }
    console.log('Two real browser time zones retained the original appointment and labelled their own date, time and seconds.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
