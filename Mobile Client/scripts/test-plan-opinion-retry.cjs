// Exercise real gateway receipts using an isolated local gateway with the mock schedule provider.
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { mkdir, writeFile } = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require("playwright");

async function run() {
    const base = new URL(process.env.SCREENMIND_WEB_BASE || "http://127.0.0.1:8765");
    assert.ok(["localhost", "127.0.0.1", "[::1]"].includes(base.hostname));
    const browser = await chromium.launch({ headless: true, executablePath: process.env.SCREENMIND_BROWSER });
    try {
        const page = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: "zh-CN", reducedMotion: "reduce" });
        const username = "opinionqa_" + randomUUID().slice(0, 8), password = randomUUID();
        let headers;
        const call = async (route, data) => {
            const response = await page.request.fetch(new URL("/v1/" + route, base).href, {
                method: data === undefined ? "GET" : "POST", headers, data
            });
            assert.ok(response.ok(), route + " HTTP " + response.status());
            return response.json();
        };
        const account = await call("auth/register", { username, password });
        headers = { Authorization: "Bearer " + account.access_token };
        assert.equal((await call("agent/health")).provider, "mock");
        const work = (await call("agent/works", { event_id: randomUUID(), subagent_id: "schedule", title: "周五的读书会" })).work;
        const eventId = randomUUID();
        await call("events", { event_id: eventId, revision: 1, event_type: "message", source_kind: "manual",
            captured_at: new Date().toISOString(), payload: { text: JSON.stringify({ schedule_changes: [
                { id: "sharing", title: "阅读分享", at: "2026-12-11T18:00:00+08:00" },
                { id: "discussion", title: "自由讨论", at: "2026-12-11T19:00:00+08:00" }
            ] }) } });
        const seed = (await call("agent/tasks", { request_id: randomUUID(), work_id: work.id,
            instruction: "把读书会的两项安排放进日程。", events: [{ event_id: eventId, revision: 1 }] })).task;
        let task = seed;
        for (let attempt = 0; attempt < 75 && ["queued", "running"].includes(task.status); attempt++) {
            await new Promise(resolve => setTimeout(resolve, 200));
            task = (await call("agent/tasks/" + seed.id)).task;
        }
        assert.equal(task.interaction.kind, "state_proposal");
        await call("agent/tasks/" + task.id + "/responses", { event_id: randomUUID(), kind: "accept_state",
            proposal_id: task.interaction.proposal_id, expected_revision: task.revision, output_version: task.interaction.output_version });
        const planPath = "agent/works/" + work.id + "/plan";
        const before = await call(planPath);
        assert.equal(before.confirmed.length, 2);
        const receipts = [];
        await page.route("**/plan/opinions", async route => {
            const response = await route.fetch();
            const result = await response.json();
            receipts.push({ id: route.request().postDataJSON().event_id, task: result.task?.id, replayed: result.replayed, status: response.status() });
            if (receipts.length === 1) await route.abort("connectionreset");
            else await route.fulfill({ response });
        });
        const login = async () => {
            await page.locator("#username").fill(username);
            await page.locator("#password").fill(password);
            await page.locator("#sign-in").click();
            await page.locator("#board").waitFor();
        };
        await page.goto(new URL("/plans", base).href);
        await login();
        const card = index => page.locator(".open-card").nth(index);
        const opinion = page.locator("#opinion");
        const submit = page.locator("#opinion-submit");
        const text = "希望自由讨论多留十分钟。";
        await card(0).click();
        await opinion.fill(text);
        await submit.click();
        await page.getByText(/可以重试|再试一次/).waitFor();
        assert.equal(await opinion.inputValue(), text);
        const out = process.env.SCREENMIND_QA_OUTPUT;
        if (out) {
            await mkdir(out, { recursive: true });
            await page.screenshot({ path: path.join(out, "interrupted.png") });
        }
        await submit.click();
        await page.locator("#card-detail").waitFor({ state: "hidden" });
        const after = await call(planPath);
        const report = { real_local_gateway: true, browser: "Edge / Chromium", synthetic_mock_schedule: true,
            send_count: receipts.length, accepted_responses: receipts.map(r => r.status), unique_event_ids: new Set(receipts.map(r => r.id)).size,
            unique_adjustment_tasks: new Set(receipts.map(r => r.task)).size, replayed: receipts.map(r => r.replayed),
            confirmed_version_before: before.version, confirmed_version_after: after.version };
        if (out) await writeFile(path.join(out, "receipts.json"), JSON.stringify(report, null, 2));
        assert.deepEqual(receipts.map(r => r.status), [202, 202]);
        assert.equal(report.unique_event_ids, 1);
        assert.equal(report.unique_adjustment_tasks, 1);
        assert.deepEqual(report.replayed, [false, true]);
        assert.equal(before.version, after.version);

        await page.locator("#confirmed").click();
        await card(0).click();
        assert.equal(await opinion.inputValue(), "");
        await opinion.fill("下次留些时间提问。");
        await page.locator("#detail-close").click();
        await card(1).click();
        assert.equal(await opinion.inputValue(), "");
        await page.locator("#detail-close").click();
        await card(0).click();
        assert.equal(await opinion.inputValue(), "下次留些时间提问。");
        if (out) await page.screenshot({ path: path.join(out, "restored-draft.png") });
        await page.locator("#detail-close").click();
        await page.locator("#logout").click();
        await login();
        await page.locator("#confirmed").click();
        await card(0).click();
        assert.equal(await opinion.inputValue(), "");
        assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
        console.log("Passed: one adjustment after losing a real accepted response, confirmed plan unchanged, drafts isolated by card and cleared at sign-out.");
    } finally { await browser.close(); }
}
run().catch(error => { console.error(error); process.exitCode = 1; });
