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
    const page = await browser.newPage({viewport:{width:1360,height:900},locale:'zh-CN',colorScheme:'dark'});
    const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    async function mode(scheme) {
      await page.emulateMedia({colorScheme:scheme});
      await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));
    }
    async function sample() {
      return page.evaluate(()=>{
        const canvas=document.createElement('canvas');
        canvas.width=canvas.height=1;
        const ctx=canvas.getContext('2d',{willReadFrequently:true});
        function rgba(color) {
          ctx.clearRect(0,0,1,1);
          ctx.fillStyle=color; ctx.fillRect(0,0,1,1);
          return Array.from(ctx.getImageData(0,0,1,1).data);
        }
        function over(fore,back) {return fore.slice(0,3).map((c,i)=>(c*fore[3]+back[i]*(255-fore[3]))/255);}
        function lum(rgb) {
          const channels=rgb.map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);
          return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;
        }
        function colors(selector) {
          const el=document.querySelector(selector);
          const stack=[];
          for(let n=el;n;n=n.parentElement)stack.unshift(rgba(getComputedStyle(n).backgroundColor));
          const background=stack.reduce((result,color)=>over(color,result),[255,255,255]);
          const text=over(rgba(getComputedStyle(el).color),background);
          const [a,b]=[lum(text),lum(background)].sort((x,y)=>y-x);
          return {background:background.map(Math.round),background_luminance:+lum(background).toFixed(4),
            text_contrast:+((a+.05)/(b+.05)).toFixed(2)};
        }
        const intro=getComputedStyle(document.querySelector('.intro'));
        const textLight=lum(rgba(getComputedStyle(document.querySelector('.intro h1')).color).slice(0,3));
        const heroContrast=Math.min(...['--hero-start','--hero-end'].map(token=>{
          const bg=lum(rgba(intro.getPropertyValue(token).trim()).slice(0,3));
          return (Math.max(textLight,bg)+.05)/(Math.min(textLight,bg)+.05);
        }));
        return {system_dark:matchMedia('(prefers-color-scheme: dark)').matches,hero_text_contrast:+heroContrast.toFixed(2),
          canvas:colors('.workbench'),card:colors('.task-panel'),body:colors('#followup'),secondary:colors('.panel-caption'),
          button:colors('#new-task'),confirm:colors('#confirm [data-button-label]'),status:colors('#task-status'),
          horizontal_overflow:document.documentElement.scrollWidth>innerWidth};
      });
    }
    await page.goto(base.href);
    await page.locator('#mode-label').filter({hasText:'演示模式'}).waitFor();
    await page.locator('#load-demo').click();
    await page.waitForFunction(()=>document.querySelectorAll('#screen-list input:checked').length===2);
    await page.locator('#instruction').fill('整理读书会的材料和集合时间');
    await page.locator('#create-submit').click();
    const ring=page.locator('.progress-ring'), button=page.locator('#new-task');
    const motion = async () => ({
      indicator:await ring.evaluate(el=>getComputedStyle(el).animationName),
      button:await button.evaluate(el=>getComputedStyle(el).transitionDuration),
    });
    const standard=await motion();
    assert.equal(standard.indicator,'workbench-spin');
    assert.notEqual(standard.button,'0s');
    await page.emulateMedia({reducedMotion:'reduce'});
    const reduced=await motion();
    assert.deepEqual(reduced,{indicator:'none',button:'0s'});
    await page.emulateMedia({reducedMotion:'no-preference'});
    await page.locator('#task-status').filter({hasText:'等待你确认'}).waitFor();
    const report={motion:{standard,reduced}};
    for (const scheme of ['dark','light']) {
      await mode(scheme);
      await page.setViewportSize({width:1360,height:900});
      await page.evaluate(()=>window.scrollTo(0,0));
      report[scheme]=await sample();
      if(out)await page.screenshot({path:path.join(out,scheme+'-desktop.png')});
      await page.setViewportSize({width:390,height:844});
      await page.evaluate(()=>window.scrollTo(0,0));
      if(out)await page.screenshot({path:path.join(out,scheme+'-phone.png')});
      await page.locator('#task-title').evaluate(el=>el.scrollIntoView({block:'start'}));
      if(out)await page.screenshot({path:path.join(out,scheme+'-card.png')});
      await page.setViewportSize({width:320,height:740});
      assert.equal((await sample()).horizontal_overflow,false);
      await page.evaluate(()=>window.scrollTo(0,0));
      if(out)await page.screenshot({path:path.join(out,scheme+'-320.png')});
    }
    const note=page.locator('#followup');
    await note.fill('我再核对一下集合时间');
    await note.evaluate(el=>el.setSelectionRange(2,5));
    await mode('dark');
    report.live_switch_preserved_cursor=await note.evaluate(el=>el===document.activeElement &&
      el.value==='我再核对一下集合时间' && el.selectionStart===2 && el.selectionEnd===5);
    if(out)await fs.writeFile(path.join(out,'result.json'),JSON.stringify(report,null,2));
    assert.equal(report.dark.system_dark,true);
    assert.equal(report.light.system_dark,false);
    for(const key of ['canvas','card','body']) {
      assert.ok(report.dark[key].background_luminance<.12,JSON.stringify(report.dark[key]));
      assert.ok(report.light[key].background_luminance>.75,JSON.stringify(report.light[key]));
    }
    for(const scheme of ['light','dark']) {
      assert.ok(report[scheme].hero_text_contrast>=4.5);
      assert.ok(report[scheme].button.background[2]>report[scheme].button.background[0]);
      for(const key of ['body','secondary','button','confirm','status']) {
        assert.ok(report[scheme][key].text_contrast>=4.5,scheme+' '+key+' '+JSON.stringify(report[scheme][key]));
      }
    }
    assert.ok(report.live_switch_preserved_cursor);
    assert.deepEqual(errors,[]);
    console.log('Light and dark followed the OS at three widths, with readable controls and the same active draft.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
