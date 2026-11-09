"""Plugin contract. Plugins receive a snapshot, never unrestricted database access."""
from dataclasses import dataclass
from typing import Protocol


class ToolError(Exception):
    pass


@dataclass(frozen=True)
class AgentContext:
    task: dict
    memory: dict | None
    source_refs: frozenset[str]


@dataclass(frozen=True)
class Proposal:
    card: dict | None = None
    memory: dict | None = None
    question: dict | None = None


class SubAgent(Protocol):
    id: str
    name: str
    description: str
    keywords: tuple[str, ...]
    requires_work: bool
    system_prompt: str
    output_tool: str
    output_schema: dict

    def propose(self, arguments: dict, context: AgentContext) -> Proposal: ...
    def mock_reply(self, messages: list[dict]) -> dict: ...


def tool_call(name, arguments):
    import json
    return {"role": "assistant", "content": None, "tool_calls": [{
        "id": "demo_" + name, "type": "function", "function": {
            "name": name, "arguments": json.dumps(arguments, ensure_ascii=False)}}]}


READ_TOOL = {"type": "function", "function": {
    "name": "read_screens", "description": "读取当前任务授权的屏幕和所属事项的已确认记忆快照。不接受任意 ID。",
    "parameters": {"type": "object", "properties": {}, "additionalProperties": False}}}
