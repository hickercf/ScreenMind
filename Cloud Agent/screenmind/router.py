"""Explicit/work-bound routing; capability keywords only when neither is given."""
from .store import DomainError


class Router:
    def __init__(self, registry):
        self.registry = registry

    def route(self, request, work=None):
        selected = request.get("subagent_id")
        if work:
            if selected and selected != work["subagent_id"]:
                raise DomainError("SubAgent 与事项类型不匹配", 422)
            selected, reason = work["subagent_id"], "work_binding"
        elif selected:
            reason = "explicit_selection"
        else:
            text = request["instruction"].casefold()
            matches = [a.id for a in self.registry.agents.values() if any(k.casefold() in text for k in a.keywords)]
            if len(matches) > 1:
                raise DomainError("多个能力匹配，请明确选择 SubAgent", 422)
            selected, reason = (matches[0] if matches else "information"), "capability_rules"
        agent = self.registry.get(selected)
        if agent.requires_work and not work:
            raise DomainError("该 SubAgent 需要独立事项，请先创建事项并传入 work_id", 422)
        return agent.id, reason
