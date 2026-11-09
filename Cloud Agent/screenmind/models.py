from typing import Annotated, Literal
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints, model_validator

Identifier = Annotated[str, StringConstraints(pattern=r"^[a-zA-Z0-9_.:-]{1,100}$")]
Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]
ShortText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Block(StrictModel):
    id: Identifier
    text: Text
    kind: Literal["paragraph", "chat", "card", "key_value"] = "paragraph"
    speaker: str | None = Field(default=None, max_length=100)
    # Coordinates are optional, normalized to 0..1000 by the Android adapter.
    bbox: list[float] | None = Field(default=None, min_length=4, max_length=4)

    @model_validator(mode="after")
    def coordinates(self):
        if self.bbox is not None:
            x1, y1, x2, y2 = self.bbox
            if not (0 <= x1 <= x2 <= 1000 and 0 <= y1 <= y2 <= 1000):
                raise ValueError("bbox must be normalized [left, top, right, bottom]")
        return self


class ScreenInput(StrictModel):
    event_id: Identifier
    session_id: Identifier
    app_package: str = Field(min_length=1, max_length=150)
    app_name: str = Field(min_length=1, max_length=100)
    page_title: str = Field(max_length=200)
    captured_at: AwareDatetime
    blocks: list[Block] = Field(min_length=1, max_length=60)

    @model_validator(mode="after")
    def unique_blocks(self):
        if len({b.id for b in self.blocks}) != len(self.blocks):
            raise ValueError("Block IDs must be unique within a screen")
        if sum(len(b.text) for b in self.blocks) > 24000:
            raise ValueError("A screen must contain at most 24000 text characters")
        return self


class TaskInput(StrictModel):
    event_id: Identifier
    instruction: Text
    screen_ids: list[Identifier] = Field(min_length=1, max_length=20)
    work_id: Identifier | None = None
    subagent_id: Identifier | None = None


class WorkInput(StrictModel):
    event_id: Identifier
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
    subagent_id: Identifier


class ActionInput(StrictModel):
    event_id: Identifier
    expected_revision: int = Field(ge=1)
    kind: Literal["revise", "confirm", "cancel", "retry"]
    message: str = Field(default="", max_length=4000)
    card_version: int | None = Field(default=None, ge=1)
    edits: dict[Identifier, ShortText] = Field(default_factory=dict, max_length=24)
    screen_ids: list[Identifier] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def action_shape(self):
        if self.kind != "revise" and (self.edits or self.screen_ids or self.message):
            raise ValueError("Only revise accepts edits, message or additional screens")
        if self.kind == "revise" and not (self.message.strip() or self.edits or self.screen_ids):
            raise ValueError("Provide a message, edits or additional screens")
        if (self.kind == "confirm" or self.edits) and self.card_version is None:
            raise ValueError("card_version is required")
        return self


class CardField(StrictModel):
    id: Identifier
    label: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
    value: ShortText
    source_refs: list[str] = Field(min_length=1, max_length=20)
    origin: Literal["source", "user"] = "source"
    editable: bool = True


class Card(StrictModel):
    type: Literal["information"] = "information"
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
    summary: ShortText
    fields: list[CardField] = Field(min_length=1, max_length=24)

    @model_validator(mode="after")
    def unique_fields(self):
        if len({f.id for f in self.fields}) != len(self.fields):
            raise ValueError("Card field IDs must be unique")
        return self

