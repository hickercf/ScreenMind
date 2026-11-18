"use strict";

const $ = id => document.getElementById(id);
const state = { token: "", work: "", snapshot: null, request: 0, busy: false,
    mode: "confirmed", signature: "", page: 0, draft: "" };
const names = { party: "聚会协调", schedule: "日程安排", information: "信息整理" };

function el(tag, value, className) {
    const node = document.createElement(tag);
    if (value !== undefined) node.textContent = value;
    if (className) node.className = className;
    return node;
}

function tone(value) {
    return ["positive", "attention", "muted"].includes(value) ? value : "";
}

async function api(path, data) {
    const headers = {};
    if (state.token) headers.Authorization = "Bearer " + state.token;
    if (data !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, { method: data === undefined ? "GET" : "POST",
        headers, cache: "no-store", body: data === undefined ? undefined : JSON.stringify(data) });
    if (!response.ok) {
        const details = await response.json().catch(() => ({}));
        throw Object.assign(new Error(typeof details.detail === "string" ? details.detail : "暂时无法读取方案，请稍后重试。"),
            { status: response.status });
    }
    return response.json();
}

function createCard(card) {
    const article = el("article", undefined, "slide");
    article.dataset.card = card.id;
    article.append(el("h2", card.title), el("p", card.value, "value " + tone(card.tone)));
    if (card.detail) article.append(el("p", card.detail, "detail"));
    if (card.segments.length) {
        const bar = el("div", undefined, "segments");
        bar.setAttribute("aria-label", card.segments.map(x => `${x.label} ${x.count} 人`).join("，"));
        for (const segment of card.segments) {
            const span = el("span", undefined, tone(segment.tone));
            span.style.flexGrow = segment.count;
            bar.append(span);
        }
        article.append(bar);
    }
    if (card.rows.length) {
        const rows = el("dl");
        for (const row of card.rows) {
            const pair = el("div");
            pair.append(el("dt", row.label), el("dd", row.value, tone(row.tone)));
            rows.append(pair);
        }
        article.append(rows);
    }
    return article;
}

function showPosition() {
    const length = $("deck").children.length;
    $("position").textContent = length ? `${state.page + 1} / ${length}` : "";
    $("prev").disabled = state.page <= 0;
    $("next").disabled = state.page >= length - 1;
}

function move(index, behavior = "smooth") {
    const deck = $("deck");
    const card = deck.children[index];
    if (!card) return;
    const offset = card.offsetLeft - deck.children[0].offsetLeft;
    deck.scrollTo({ left: offset, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : behavior });
}

function render(plan) {
    const signature = JSON.stringify([state.mode, plan]);
    if (signature === state.signature) return;
    state.signature = signature;
    $("board").hidden = false;
    $("kind").textContent = names[plan.subagent_id] || "事项";
    $("work-title").textContent = plan.title;
    $("confirmed").textContent = plan.version ? "已确认 · v" + plan.version : "还未确认";
    const preview = state.mode === "candidate" && !!plan.candidate;
    $("confirmed").setAttribute("aria-pressed", !preview);
    $("candidate").setAttribute("aria-pressed", preview);
    $("candidate").hidden = !plan.candidate;
    $("change").textContent = preview ? plan.candidate.summary : "";
    $("change").hidden = !preview || !$("change").textContent;
    const cards = preview ? plan.candidate.cards : plan.confirmed;
    const deck = $("deck");
    const activeId = deck.children[state.page]?.dataset.card;
    $("no-cards").hidden = cards.length > 0;
    deck.hidden = cards.length === 0;
    deck.replaceChildren(...cards.map(createCard));
    const match = cards.findIndex(card => card.id === activeId);
    state.page = match >= 0 ? match : 0;
    move(state.page, "instant");
    showPosition();
}

async function load() {
    const request = ++state.request;
    const selected = state.work;
    try {
        const { works } = await api("/v1/agent/works");
        if (request !== state.request) return;
        const dropdown = $("work-select");
        const options = JSON.stringify(works.map(({id,title}) => [id,title]));
        if (dropdown.dataset.signature !== options) {
            dropdown.dataset.signature = options;
            dropdown.replaceChildren(...works.map(work => {
                const option = el("option", work.title);
                option.value = work.id;
                return option;
            }));
        }
        state.work = works.some(work => work.id === selected) ? selected : works[0]?.id || "";
        dropdown.value = state.work;
        $("empty").hidden = !!state.work;
        if (!state.work) {
            $("board").hidden = true;
            state.snapshot = null;
            return;
        }
        const plan = await api("/v1/agent/works/" + encodeURIComponent(state.work) + "/plan");
        if (request !== state.request) return;
        const draft = plan.candidate ? [plan.candidate.task_id, plan.candidate.revision, plan.candidate.card_version].join(":") : "";
        if (draft !== state.draft || plan.work_id !== state.snapshot?.work_id) {
            state.draft = draft;
            state.mode = draft ? "candidate" : "confirmed";
        }
        state.snapshot = plan;
        render(plan);
        $("status").textContent = "";
    } catch (error) {
        if (request !== state.request) return;
        if (error.status === 401) clear();
        $("status").textContent = error.status === 401 ? "登录已过期，请重新登录。" : "同步暂不可用，稍后自动重试。";
    }
}

function clear() {
    Object.assign(state, { token: "", work: "", snapshot: null, signature: "", draft: "", page: 0 });
    state.request++;
    $("deck").replaceChildren();
    $("work-title").textContent = "";
    $("change").textContent = "";
    $("work-select").replaceChildren();
    delete $("work-select").dataset.signature;
    $("board").hidden = true;
    $("workspace").hidden = true;
    $("login-panel").hidden = false;
    $("logout").hidden = true;
}

$("login").addEventListener("submit", async event => {
    event.preventDefault();
    if (state.busy) return;
    state.busy = true;
    $("sign-in").disabled = true;
    $("status").textContent = "正在登录…";
    try {
        const session = await api("/v1/auth/login", { username: $("username").value.trim(), password: $("password").value });
        state.token = session.access_token;
        $("login-panel").hidden = true;
        $("workspace").hidden = false;
        $("logout").hidden = false;
        await load();
    } catch (error) {
        $("status").textContent = error.message;
    } finally {
        $("password").value = "";
        $("sign-in").disabled = false;
        state.busy = false;
    }
});
$("logout").onclick = async () => {
    const outgoing = api("/v1/auth/logout", {});
    clear();
    $("status").textContent = "";
    await outgoing.catch(() => {});
};
$("work-select").onchange = () => {
    $("board").hidden = true;
    state.work = $("work-select").value;
    state.snapshot = null;
    state.signature = "";
    state.page = 0;
    $("deck").replaceChildren();
    load();
};
for (const mode of ["confirmed", "candidate"]) $(mode).onclick = () => {
    if (!state.snapshot) return;
    state.mode = mode;
    render(state.snapshot);
};
$("prev").onclick = () => move(state.page - 1);
$("next").onclick = () => move(state.page + 1);
$("deck").onkeydown = event => {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        move(state.page + (event.key === "ArrowRight" ? 1 : -1));
    }
};
let frame;
$("deck").addEventListener("scroll", () => {
    if (frame) return;
    frame = requestAnimationFrame(() => {
        frame = undefined;
        const deck = $("deck");
        if (!deck.children.length) return;
        let page = 0;
        let closest = Infinity;
        const max = deck.scrollWidth - deck.clientWidth;
        for (const [i, card] of Array.from(deck.children).entries()) {
            const distance = Math.abs(Math.min(max, card.offsetLeft - deck.children[0].offsetLeft) - deck.scrollLeft);
            if (distance <= closest) { closest = distance; page = i; }
        }
        if (page !== state.page) { state.page = page; showPosition(); }
    });
}, { passive: true });
setInterval(() => { if (state.token && !document.hidden && !state.busy) load(); }, 2500);
document.addEventListener("visibilitychange", () => { if (state.token && !document.hidden) load(); });
