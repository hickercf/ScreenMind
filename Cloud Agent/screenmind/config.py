from dataclasses import dataclass, field
import os
from pathlib import Path
from urllib.parse import urlparse


def local_settings(path):
    """Read literal KEY=VALUE settings; no interpolation or code execution."""
    if not path.exists():
        return {}
    values = {}
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not key.startswith("SCREENMIND_") or not key.replace("_", "").isalnum():
            raise ValueError(f"Invalid local configuration at line {number}")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Unclosed local configuration quote at line {number}")
            value = value[1:-1]
        values[key] = value
    return values


@dataclass(frozen=True)
class Settings:
    db_path: Path = field(default_factory=lambda: Path("data/screenmind.sqlite3"))
    provider: str = "mock"
    base_url: str = ""
    model: str = ""
    model_key: str = field(default="", repr=False)
    api_key: str = field(default="", repr=False)
    allowed_hosts: tuple[str, ...] = ("localhost", "127.0.0.1", "[::1]")
    concurrency: int = 2
    max_steps: int = 6
    task_timeout: float = 120
    model_timeout: float = 40
    mock_delay: float = 0.35
    # One service deployment owns one workspace; never trust a client-supplied owner.
    owner: str = "local-workspace"
    memory_mode: str = "basic"
    memory_extractor: str = "raw"
    memory_planning: bool = False
    evidence_review: bool = False
    embedding: str = "hash-demo"
    embedding_model_path: str = ""
    index_path: Path = field(default_factory=lambda: Path("data/memory-index"))
    action_path: Path = field(default_factory=lambda: Path("data/calendar-exports"))
    caldav_url: str = ""
    caldav_username: str = ""
    caldav_password: str = field(default="", repr=False)

    def __post_init__(self):
        if self.memory_mode not in {"basic", "hybrid", "structured"} or self.memory_extractor not in {"raw", "simplemem", "structured"} or self.embedding not in {"hash-demo", "qwen"}:
            raise ValueError("Invalid memory configuration")
        if self.caldav_url:
            url = urlparse(self.caldav_url)
            if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
                raise ValueError("CalDAV requires a fixed HTTPS calendar collection URL")
        if self.provider not in {"mock", "openai-compatible"}:
            raise ValueError("SCREENMIND_PROVIDER must be mock or openai-compatible")
        if not 1 <= self.concurrency <= 8 or not 2 <= self.max_steps <= 20:
            raise ValueError("Invalid concurrency or max_steps")
        if self.task_timeout <= 0 or self.model_timeout <= 0:
            raise ValueError("Timeouts must be positive")
        if self.provider != "mock" or self.memory_extractor in {"simplemem", "structured"} or self.memory_planning or self.evidence_review:
            url = urlparse(self.base_url)
            if not (self.model_key and self.model and url.hostname):
                raise ValueError("Real mode requires MODEL_BASE_URL, MODEL and MODEL_API_KEY")
            if url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}):
                raise ValueError("Use HTTPS for remote model endpoints")
            if url.username or url.password or url.query or url.fragment:
                raise ValueError("Model base URL must not include credentials, query or fragment")

    @classmethod
    def from_env(cls, env_file=None):
        # Explicit process settings take precedence; the file stays local and gitignored.
        values = local_settings(Path(env_file) if env_file is not None else Path(__file__).resolve().parent.parent / ".env")
        values.update(os.environ)
        return cls(
            db_path=Path(values.get("SCREENMIND_DB_PATH", "data/screenmind.sqlite3")),
            provider=values.get("SCREENMIND_PROVIDER", "mock"),
            base_url=values.get("SCREENMIND_MODEL_BASE_URL", "").rstrip("/"),
            model=values.get("SCREENMIND_MODEL", ""),
            model_key=values.get("SCREENMIND_MODEL_API_KEY", ""),
            api_key=values.get("SCREENMIND_API_KEY", ""),
            allowed_hosts=tuple(values.get("SCREENMIND_ALLOWED_HOSTS", "localhost,127.0.0.1,[::1]").split(",")),
            concurrency=int(values.get("SCREENMIND_CONCURRENCY", "2")),
            max_steps=int(values.get("SCREENMIND_MAX_STEPS", "6")),
            task_timeout=float(values.get("SCREENMIND_TASK_TIMEOUT", "120")),
            model_timeout=float(values.get("SCREENMIND_MODEL_TIMEOUT", "40")),
            memory_mode=values.get("SCREENMIND_MEMORY_MODE", "basic"),
            memory_extractor=values.get("SCREENMIND_MEMORY_EXTRACTOR", "raw"),
            memory_planning=values.get("SCREENMIND_MEMORY_PLANNING", "false").lower() == "true",
            evidence_review=values.get("SCREENMIND_EVIDENCE_REVIEW", "false").lower() == "true",
            embedding=values.get("SCREENMIND_EMBEDDING", "hash-demo"),
            embedding_model_path=values.get("SCREENMIND_EMBEDDING_MODEL_PATH", ""),
            index_path=Path(values.get("SCREENMIND_INDEX_PATH", "data/memory-index")),
            action_path=Path(values.get("SCREENMIND_ACTION_PATH", "data/calendar-exports")),
            caldav_url=values.get("SCREENMIND_CALDAV_URL", ""),
            caldav_username=values.get("SCREENMIND_CALDAV_USERNAME", ""),
            caldav_password=values.get("SCREENMIND_CALDAV_PASSWORD", ""),
        )

