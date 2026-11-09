"""cloud-v1 interactions: information, clarification, state and authorized actions."""
from typing import Annotated, Literal
from pydantic import Field, TypeAdapter
from .models import StrictModel, Identifier, Text, ShortText, Card

PROTOCOL = "cloud-v1"


class Envelope(StrictModel):
    contract_version: Literal["cloud-v1"] = PROTOCOL
    task_id: Identifier
    work_id: Identifier | None
    revision: int = Field(ge=1)
    output_version: int = Field(ge=1)


class Information(Envelope):
    kind: Literal["information"] = "information"
    card: Card


class Clarification(Envelope):
    kind: Literal["clarification"] = "clarification"
    question_id: Identifier
    question: ShortText
    source_refs: list[str] = Field(min_length=1, max_length=20)


class StateProposal(Envelope):
    kind: Literal["state_proposal"] = "state_proposal"
    proposal_id: Identifier
    base_state_version: int = Field(ge=0)
    candidate_state: dict
    card: Card


class ActionProposal(Envelope):
    kind: Literal["action_proposal"] = "action_proposal"
    action_id: Identifier
    base_state_version: int = Field(ge=0)
    tool: Identifier
    parameters: dict
    execution_enabled: bool = False


Interaction = Annotated[Information | Clarification | StateProposal | ActionProposal, Field(discriminator="kind")]
interaction_adapter = TypeAdapter(Interaction)


class Response(StrictModel):
    contract_version: Literal["cloud-v1"] = PROTOCOL
    event_id: Identifier
    expected_revision: int = Field(ge=1)
    output_version: int = Field(ge=1)


class Acknowledge(Response):
    kind: Literal["acknowledge"]


class Answer(Response):
    kind: Literal["answer"]
    question_id: Identifier
    answer: Text


class StateResponse(Response):
    kind: Literal["accept_state", "reject_state"]
    proposal_id: Identifier


class ActionResponse(Response):
    kind: Literal["authorize_action", "reject_action"]
    action_id: Identifier


InteractionResponse = Annotated[Acknowledge | Answer | StateResponse | ActionResponse, Field(discriminator="kind")]


class Question(StrictModel):
    question: ShortText
    source_refs: list[str] = Field(min_length=1, max_length=20)


QUESTION_TOOL = {"type": "function", "function": {"name": "ask_clarification",
    "description": "必要事实缺失或冲突时提出一个问题，等待用户回答；不能同时提交状态。",
    "parameters": Question.model_json_schema()}}
