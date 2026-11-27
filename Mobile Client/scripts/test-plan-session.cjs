// Run against the local gateway's /plans page. Credentials and times below belong to the browser fixture.
const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const base = process.env.SCREENMIND_WEB_BASE || "http://127.0.0.1:8765";
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function run() {
    const options = { headless: true };
    if (process.env.SCREENMIND_BROWSER) options.executablePath = process.env.SCREENMIND_BROWSER;
    const browser = await chromium.launch(options);
    try {
        const page = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: "zh-CN" });
        const accounts = new Map();
        let renewals = 0, releaseRefresh, startDelayedRefresh, rejects = 0, submissions = 0;
        const revoked = [];
        const makeSession = (name, version) => ({
            access_token: name + "-access-" + version,
            refresh_token: name + "-refresh-" + version,
            user: { id: name }
        });
        await page.route("**/v1/**", async route => {
            const path = new URL(route.request().url()).pathname;
            const token = route.request().headers().authorization?.replace(/^Bearer /, "");
            const data = route.request().postDataJSON();
            let status = 200, json;
            if (path === "/v1/auth/login") {
                json = makeSession(data.username, 1);
                accounts.set(data.username, { session: json, version: 1, expired: false });
            } else if (path === "/v1/auth/refresh") {
                const account = [...accounts.values()].find(a => a.session.refresh_token === data.refresh_token);
                renewals++;
                if (!account || account.revoked) { status = 401; json = {detail:"请重新登录。"}; }
                else {
                    if (startDelayedRefresh) {
                        startDelayedRefresh();
                        await new Promise(resolve => { releaseRefresh = resolve; });
                        startDelayedRefresh = null;
                    } else await pause(150);
                    account.expired = false;
                    account.session = makeSession(account.session.user.id, ++account.version);
                    json = account.session;
                }
            } else {
                const record = [...accounts.values()].find(a => a.session.access_token === token);
                if (!record || record.expired || record.revoked) {
                    rejects++;
                    status = 401;
                    json = { detail: "请重新登录。" };
                } else {
                    const owner = record.session.user.id;
                    const title = owner === "first" ? "第一位用户的组会" : "第二位用户的读书会";
                    if (path === "/v1/auth/logout") {
                        revoked.push(owner); record.revoked = true; json = { ok:true };
                    } else if (path === "/v1/agent/works") json = { works:[{id:owner,title}] };
                    else if (path.endsWith("/opinions")) { submissions++; json = {ok:true}; }
                    else json = { work_id:owner, title, subagent_id:"schedule", version:1, candidate:null, supports_opinions:true,
                        confirmed:[{id:"time",title:"日期",value:"周五下午",rows:[],segments:[]}] };
                }
            }
            await route.fulfill({status,json}).catch(() => {});
        });
        async function login(name) {
            await page.locator("#username").fill(name);
            await page.locator("#password").fill("synthetic-password");
            await page.locator("#sign-in").click();
            await page.locator("#board").waitFor();
        }
        const read = () => page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
        await page.goto(base + "/plans");
        await login("first");
        await page.locator(".open-card").first().click();
        await page.locator("#opinion").fill("结束后留十分钟讨论。");
        accounts.get("first").expired = true;
        await read();
        await page.waitForResponse(r => r.url().endsWith("/v1/auth/refresh") && r.status() === 200, {timeout:5000});
        assert.equal(await page.locator("#opinion").inputValue(),"结束后留十分钟讨论。");
        assert.equal(await page.locator("#card-detail").isVisible(),true);
        accounts.get("first").expired = true;
        await read();
        await page.locator("#opinion-submit").click();
        await page.locator("#card-detail").waitFor({state:"hidden"});
        assert.equal(renewals,2);
        assert.equal(submissions,1);
        assert.ok(rejects >= 2);

        accounts.get("first").expired = true;
        const started = new Promise(resolve => { startDelayedRefresh = resolve; });
        await read();
        await Promise.race([started, pause(6000).then(() => { throw Error("Renewal did not start"); })]);
        await page.locator("#logout").click();
        await login("second");
        releaseRefresh();
        await page.waitForResponse(r => r.url().endsWith("/v1/auth/logout") && r.status() === 200,{timeout:5000});
        assert.deepEqual(revoked,["first"]);
        assert.equal(await page.locator("#work-title").textContent(),"第二位用户的读书会");
        accounts.get("second").expired = true;
        accounts.get("second").revoked = true;
        await read();
        await page.locator("#sign-in").waitFor();
        assert.equal(await page.locator("#login-status").textContent(),"登录已过期，请重新登录。");
        assert.equal(await page.locator("#work-title").textContent(),"");
        assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length),0);
        console.log("Passed: retained draft, single refresh for overlapping reads and submission, logout during renewal, expired session and account isolation.");
    } finally { await browser.close(); }
}
run().catch(error => { console.error(error); process.exitCode = 1; });
