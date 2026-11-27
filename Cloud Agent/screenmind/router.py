"""Explicit/work-bound routing; capability keywords only when neither is given."""
from .store import DomainError


class Router:
    def __init__(self, registry):
        self.registry = registry

    def route(self, request, work=None):
        selected = request.get("subagent_id")
        if work:
            if selected and selected != work["subagent_id"]:
                raise DomainError("所选的处理方式与当前事项不符，请检查当前事项。", 422)
            selected, reason = work["subagent_id"], "work_binding"
        elif selected:
            reason = "explicit_selection"
        else:
            text = request["instruction"].casefold()
            matches = [a.id for a in self.registry.agents.values() if any(k.casefold() in text for k in a.keywords)]
            if len(matches) > 1:
                raise DomainError("这条要求涉及多种处理方式，请选择适合的事项后再开始。", 422)
            selected, reason = (matches[0] if matches else "information"), "capability_rules"
        agent = self.registry.get(selected)
        if agent.requires_work and not work:
            raise DomainError(f"请先选择或创建一个“{agent.name}”事项，再处理这些线索。", 422)
        return agent.id, reason
