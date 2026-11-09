"""Composition root: new capabilities register here, without changing the runtime."""
from .information import InformationAgent
from .party import PartyAgent


class Registry:
    def __init__(self, agents=None):
        entries = list(agents) if agents is not None else [InformationAgent(), PartyAgent()]
        self.agents = {agent.id: agent for agent in entries}
        if len(self.agents) != len(entries):
            raise ValueError("Duplicate SubAgent id")

    def get(self, agent_id):
        from ..store import DomainError
        if agent_id not in self.agents:
            raise DomainError("未知 SubAgent", 422)
        return self.agents[agent_id]

    def catalog(self):
        return [{"id": a.id, "name": a.name, "description": a.description,
                 "requires_work": a.requires_work} for a in self.agents.values()]
