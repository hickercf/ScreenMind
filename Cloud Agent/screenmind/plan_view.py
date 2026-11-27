"""Read-only presentation shared by the authenticated web and Android plan decks."""
from typing import Literal
from pydantic import Field
from .agents.party import PartyState, money, party_time_label
from .models import ActionInput, Identifier, ShortText, StrictModel, TaskInput
from .store import DomainError


class PlanOpinion(StrictModel):
    event_id: Identifier
    card_id: Identifier
    version: int = Field(ge=0)
    text: ShortText
    mode: Literal["confirmed", "candidate"]
    task_id: Identifier | None = None
    revision: int | None = Field(default=None, ge=1)
    card_version: int | None = Field(default=None, ge=1)


def row(label, value, tone="neutral"):
    return {"label": label, "value": value, "tone": tone}


def card(key, title, value, detail="", rows=(), segments=(), tone="neutral"):
    return {"id": key, "title": title, "value": value, "detail": detail,
            "rows": list(rows), "segments": list(segments), "tone": tone}


def party_cards(data, scheduled_at=None):
    plan = PartyState.model_validate(data)
    people = {p.id: p for p in plan.participants}
    groups = [(label, tone, [p.name for p in people.values() if p.status == status])
              for status, label, tone in (
                  ("attending", "参加", "positive"),
                  ("tentative", "待确认", "attention"),
                  ("declined", "退出", "muted"))]
    count = len(groups[0][2])
    overview = card("venue", "聚会安排", plan.location, rows=[row("时间", party_time_label(plan.time, scheduled_at))])
    attendees = card("attendees", "参与情况", f"{count} 人参加",
                     rows=[row(label, "、".join(names), tone) for label, tone, names in groups if names],
                     segments=[{"label": label, "count": len(names), "tone": tone}
                               for label, tone, names in groups if names], tone="positive")
    costs = []
    if plan.fixed_cost_cents is not None:
        costs.append(row("固定总价", "¥" + money(plan.fixed_cost_cents)))
    if plan.budget_per_person_cents is not None:
        costs.append(row("人均预算", "¥" + money(plan.budget_per_person_cents)))
    estimate = "¥" + money(plan.fixed_cost_cents, count) if count and plan.fixed_cost_cents is not None else "待估算"
    budget = card("budget", "人均费用", estimate, "按确定参加者平摊固定总价", costs, tone="attention")
    responsibilities = []
    for item in plan.responsibilities:
        owner = people.get(item.owner_id)
        if item.status == "completed":
            responsibilities.append(row(item.title, f"{owner.name} · 已完成" if owner else "已完成", "positive"))
        elif not owner or owner.status == "declined":
            responsibilities.append(row(item.title, "待重新分配", "attention"))
        elif owner.status == "tentative":
            responsibilities.append(row(item.title, f"{owner.name} · 需备用负责人", "attention"))
        else:
            responsibilities.append(row(item.title, f"{owner.name} · 待完成"))
    done = sum(item.status == "completed" for item in plan.responsibilities)
    duties = card("duties", "分工进度", f"{done} / {len(responsibilities)} 已完成" if responsibilities else "待添加分工",
                  rows=responsibilities)
    return [overview, attendees, budget, duties]


def cards_for(state, fallback=()):
    if state.get("plan"):
        return party_cards(state["plan"], state.get("scheduled_at"))
    items = state.get("items")
    if isinstance(items, dict):
        return [card("slot:" + key, "日程", data["title"],
                     rows=[row("时间", data["at"]), row("进度", "已完成" if data.get("status") == "completed" else "待完成")])
                for key, data in items.items()] or [card("empty", "日程", "暂无日程条目")]
    fields = (state.get("card") or {}).get("fields") or fallback
    return [card(field["id"], field["label"], str(field["value"])) for field in fields]


def read_plan(store, wid):
    work = store.work(wid)
    version = work["memory_version"]
    confirmed = cards_for(work["state"])
    if version and not confirmed:
        confirmed = [card("record", "当前方案", work["title"], "事项状态已保存")]
    result = {"work_id": wid, "title": work["title"], "subagent_id": work["subagent_id"],
              "version": version, "confirmed": confirmed, "candidate": None}
    row = store.db.execute("""SELECT id FROM tasks
        WHERE owner=? AND work_id=? AND status='waiting_input' AND memory_proposal IS NOT NULL
        AND memory_base=? ORDER BY updated_at DESC, id DESC LIMIT 1""", (store.owner, wid, version)).fetchone()
    if row:
        task = store.task(row["id"])
        output = task["card"] or {}
        result["candidate"] = {"task_id": task["id"], "revision": task["revision"],
                               "card_version": task["card_version"], "summary": output.get("summary", ""),
                               "cards": cards_for(task["memory_proposal"], output.get("fields", ()))}
    return result


def submit_opinion(store, wid, opinion):
    body = opinion.model_dump(mode="json")
    scope = f"plan.opinion:{wid}"
    previous = store.replay(scope, body)
    if previous:
        return store.task(previous), True
    plan = read_plan(store, wid)
    if plan["version"] != opinion.version:
        raise DomainError("方案已更新，请刷新后重新提交意见")
    candidate = plan["candidate"]
    if opinion.mode == "candidate":
        if not candidate or any(candidate[key] != body[key] for key in ("task_id", "revision", "card_version")):
            raise DomainError("这张待确认卡片已更新，请重新打开")
        source_task = candidate["task_id"]
        cards = candidate["cards"]
    else:
        owner_version = store.db.execute("SELECT task_id FROM memories WHERE work_id=? AND version=?",
                                         (wid, opinion.version)).fetchone()
        if not owner_version:
            raise DomainError("还没有已确认的方案可供修改")
        source_task = owner_version["task_id"]
        cards = plan["confirmed"]
    selected = next((item for item in cards if item["id"] == opinion.card_id), None)
    if selected is None:
        raise DomainError("此卡片已变化，请重新打开", 409)
    message = f"我对「{selected['title']}」的意见：{opinion.text}\n请保留其他安排，按我的意见重新整理，待我确认。"
    source = store.task(source_task)
    if not store.sources_live(source):
        raise DomainError("原材料已删除，请添加新的线索后继续", 409)
    if opinion.mode == "candidate":
        action = ActionInput(event_id=opinion.event_id, expected_revision=opinion.revision,
                             card_version=opinion.card_version, kind="revise", message=message)
        result, replayed = store.action(source_task, action.model_dump(mode="json"))
    else:
        task = TaskInput(event_id=opinion.event_id, work_id=wid, screen_ids=source["screen_ids"],
                         instruction=message, interaction_protocol="cloud-v1")
        result, replayed = store.create_task(task.model_dump(mode="json"))
    store.receipt(scope, body, result["id"])
    return result, replayed
