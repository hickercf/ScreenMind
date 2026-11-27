// Run against a local Mobile Client gateway with Playwright available on NODE_PATH.
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const base = process.env.SCREENMIND_WEB_BASE || "http://127.0.0.1:8765";
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function run() {
    const options = { headless: true };
    if (process.env.SCREENMIND_BROWSER) options.executablePath = process.env.SCREENMIND_BROWSER;
    const browser = await chromium.launch(options);
    try {
        const page = await browser.newPage({ viewport: { width: 360, height: 740 }, locale: "zh-CN", timezoneId:"Asia/Taipei" });
        let slow = false, delayedPlan = false, revision = 1, activeLists = 0, peakLists = 0;
        let signalOldPlan;
        const oldPlanStarted = new Promise(resolve => { signalOldPlan = resolve; });
        const plan = work => ({
            work_id: work, title: work === "a" ? "早会" : "读书会", subagent_id: "schedule",
            version: revision, candidate: null,
            confirmed: [{ id: "time", title: "时间", value: "2026-12-10T18:00:00+08:00", rows: [], segments: [] }]
        });
        await page.route("**/v1/**", async route => {
            const pathname = new URL(route.request().url()).pathname;
            let json;
            if (pathname === "/v1/auth/login") json = { access_token: "synthetic" };
            else if (pathname === "/v1/auth/logout") json = {};
            else if (pathname === "/v1/agent/works") {
                activeLists++;
                peakLists = Math.max(peakLists, activeLists);
                try { if (slow) await pause(3300); }
                finally { activeLists--; }
                json = { works: [{ id: "a", title: "早会" }, { id: "b", title: "读书会" }] };
            } else if (pathname.endsWith("/plan")) {
                const work = pathname.split("/").at(-2);
                if (work === "a" && delayedPlan) {
                    signalOldPlan();
                    await pause(1200);
                }
                json = plan(work);
            } else throw Error("Unexpected test request " + pathname);
            await route.fulfill({ json });
        });
        await page.goto(base + "/plans");
        await page.locator("#username").fill("screenmind");
        await page.locator("#password").fill("synthetic-password");
        await page.locator("#sign-in").click();
        const version = page.locator("#confirmed");
        await version.filter({ hasText: "第 1 版" }).waitFor();
        await page.getByText("2026年12月10日 18:00（UTC+08:00）", {exact: true}).waitFor();
        const open = page.locator(".open-card").first();
        const focused = locator => locator.evaluate(node => node === document.activeElement);
        await page.locator("#deck").focus();
        await page.keyboard.press("Tab");
        assert.ok(await focused(open), "Tab reaches the card's detail button.");
        slow = true;
        revision = 2;
        await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
        await version.filter({ hasText: "第 2 版" }).waitFor({ timeout: 10500 });
        assert.equal(peakLists, 1, "A slower request must survive the next polling tick.");
        assert.ok(await focused(open), "Reading a newer plan must preserve focus on the same card.");

        slow = false;
        await page.keyboard.press("Enter");
        await page.locator("#card-detail").waitFor();
        revision = 3;
        await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
        await version.filter({ hasText: "第 3 版" }).waitFor();
        assert.ok(await page.locator("#card-detail").evaluate(dialog => dialog.contains(document.activeElement)));
        await page.keyboard.press("Escape");
        await page.locator("#card-detail").waitFor({state:"hidden"});
        assert.ok(await focused(open), "Closing details must return to the opener after a background refresh.");
        if (process.env.SCREENMIND_FOCUS_SCREENSHOT) await page.screenshot({path:process.env.SCREENMIND_FOCUS_SCREENSHOT});
        delayedPlan = true;
        await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
        await oldPlanStarted;
        await page.locator("#work-select").selectOption("b");
        await page.locator("#work-title").filter({ hasText: "读书会" }).waitFor();
        await pause(1400);
        assert.equal(await page.locator("#work-select").inputValue(), "b");
        assert.equal(await page.locator("#work-title").textContent(), "读书会");

        slow = true;
        await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
        await page.getByRole("button", { name: "退出登录" }).click();
        await page.locator("#sign-in").waitFor();
        await pause(3500);
        assert.equal(await page.locator("#work-title").textContent(), "");
        assert.equal(await page.locator("#workspace").isVisible(), false);
        console.log("Passed: slow automatic refresh, readable date, keyboard focus after refresh and modal dismissal, superseded selection and logout during refresh.");
    } finally { await browser.close(); }
}
run().catch(error => { console.error(error); process.exitCode = 1; });
