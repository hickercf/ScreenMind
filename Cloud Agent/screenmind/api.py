import asyncio
from contextlib import asynccontextmanager
import hmac
from pathlib import Path
from urllib.parse import urlparse
from fastapi import FastAPI, Query, Request
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.trustedhost import TrustedHostMiddleware
from .config import Settings
from .models import ActionInput, ScreenInput, TaskInput, WorkInput
from .agents import Registry
from .providers import CompatibleProvider, MockProvider
from .store import DomainError, Store, encode
from .worker import Runtime


class ProcessLock:
    """Refuse multiple schedulers sharing one DB. OS releases lock on crash."""
    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = open(str(path) + ".lock", "a+b")
        try:
            # On Windows even reading another process's locked byte is forbidden.
            self.file.seek(0, 2)
            if self.file.tell() == 0:
                self.file.write(b"0")
                self.file.flush()
            self.file.seek(0)
            import os
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError("Database already in use; run exactly one Uvicorn worker") from None

    def close(self):
        self.file.close()


def create_app(settings=None, provider=None, registry=None):
    settings = settings or Settings.from_env()
    registry = registry or Registry()

    @asynccontextmanager
    async def lifespan(app):
        lock = ProcessLock(settings.db_path)
        store = None
        runtime = None
        try:
            store = Store(settings.db_path, settings.owner, registry)
            selected = provider or (MockProvider(settings.mock_delay, registry) if settings.provider == "mock" else CompatibleProvider(settings))
            runtime = Runtime(store, selected, settings)
            app.state.store, app.state.runtime = store, runtime
            await runtime.start()
            yield
        finally:
            if runtime:
                await runtime.close()
            if store:
                store.close()
            lock.close()

    app = FastAPI(title="ScreenMind Super Agent", version="0.2.0", lifespan=lifespan)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(settings.allowed_hosts))

    @app.middleware("http")
    async def boundary(request: Request, call_next):
        if request.url.path.startswith("/api/"):
            if settings.api_key:
                supplied = request.headers.get("authorization", "")
                if not hmac.compare_digest(supplied.encode(), ("Bearer " + settings.api_key).encode()):
                    return JSONResponse({"detail": "需要有效的 API Bearer Key"}, status_code=401)
            elif not request.client or request.client.host not in {"127.0.0.1", "::1", "testclient"}:
                return JSONResponse({"detail": "未配置 API Key，仅允许本机访问"}, status_code=403)
            origin = request.headers.get("origin")
            if origin:
                parsed = urlparse(origin)
                if parsed.scheme not in {"http", "https"} or parsed.netloc != request.headers.get("host"):
                    return JSONResponse({"detail": "不允许跨站访问"}, status_code=403)
            if request.method in {"POST", "PUT", "PATCH"}:
                if request.headers.get("content-type", "").split(";")[0] != "application/json":
                    return JSONResponse({"detail": "请使用 application/json"}, status_code=415)
                # Limit streamed bodies too; Content-Length is not trusted as the only check.
                chunks, size = [], 0
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > 512_000:
                        return JSONResponse({"detail": "请求体过大"}, status_code=413)
                    chunks.append(chunk)
                request._body = b"".join(chunks)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        if request.url.path == "/" or request.url.path.startswith("/static/"):
            response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        return response

    @app.exception_handler(DomainError)
    async def domain_error(request, exc):
        return JSONResponse({"detail": str(exc)}, status_code=exc.status)

    @app.get("/api/health")
    async def health():
        scheduler = app.state.runtime.scheduler
        return {"status": "ok" if scheduler and not scheduler.done() else "degraded",
                "provider": settings.provider, "model": settings.model if settings.provider != "mock" else "deterministic-demo",
                "concurrency": settings.concurrency, "scope": "single-user", "router": "registry-and-work-binding"}

    @app.get("/api/subagents")
    async def subagents():
        return {"subagents": registry.catalog()}

    @app.post("/api/works", status_code=201)
    async def create_work(body: WorkInput):
        work, replayed = app.state.store.create_work(body.model_dump())
        return {"work": work, "replayed": replayed}

    @app.get("/api/works")
    async def works():
        return {"works": app.state.store.list_works()}

    @app.get("/api/works/{wid}")
    async def work(wid: str):
        return {"work": app.state.store.work(wid), "history": app.state.store.memory_history(wid)}

    @app.post("/api/screens", status_code=201)
    async def screen(body: ScreenInput):
        value, replayed = app.state.store.save_screen(body.model_dump(mode="json"))
        return {"screen": value, "replayed": replayed}

    @app.get("/api/screens")
    async def screens():
        return {"screens": app.state.store.list_screens()}

    @app.post("/api/tasks", status_code=202)
    async def task(body: TaskInput):
        value, replayed = app.state.store.create_task(body.model_dump(mode="json"))
        return {"task": value, "replayed": replayed}

    @app.get("/api/tasks")
    async def tasks():
        return {"tasks": app.state.store.list_tasks()}

    @app.get("/api/tasks/{tid}")
    async def detail(tid: str):
        return {"task": app.state.store.task(tid)}

    @app.post("/api/tasks/{tid}/actions", status_code=202)
    async def action(tid: str, body: ActionInput):
        value, replayed = app.state.store.action(tid, body.model_dump(mode="json"))
        if not replayed:
            app.state.runtime.interrupt(tid)
        return {"task": value, "replayed": replayed}

    @app.get("/api/events")
    async def events(after: int = Query(0, ge=0), task_id: str | None = None):
        if task_id:
            app.state.store.task(task_id)
        return {"events": app.state.store.events(after, task_id)}

    @app.get("/api/events/stream")
    async def stream(request: Request, after: int = Query(0, ge=0)):
        try:
            cursor = max(after, int(request.headers.get("last-event-id", "0")))
        except ValueError:
            return JSONResponse({"detail": "无效的 Last-Event-ID"}, status_code=422)

        async def generate():
            nonlocal cursor
            idle = 0
            while not await request.is_disconnected():
                batch = app.state.store.events(cursor)
                for event in batch:
                    cursor = event["seq"]
                    yield f"id: {cursor}\nevent: update\ndata: {encode(event)}\n\n"
                idle += 1
                if idle % 30 == 0:
                    yield ": heartbeat\n\n"
                await asyncio.sleep(0.5)
        return StreamingResponse(generate(), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})

    static = Path(__file__).parent / "static"
    app.mount("/static", StaticFiles(directory=static), name="static")

    @app.get("/", include_in_schema=False)
    async def index():
        return FileResponse(static / "index.html")

    return app
