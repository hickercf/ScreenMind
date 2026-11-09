"""Opt-in real SimpleMem -> LanceDB -> original-evidence retrieval integration check."""
import argparse
import asyncio
import json
from pathlib import Path
import time
import uuid

from screenmind.config import Settings
from screenmind.memory import BasicMemoryProvider
from screenmind.memory_service import MemoryService
from screenmind.models import ScreenInput, TaskInput
from screenmind.retrieval import HashDemoEmbedding, QwenEmbedding, LanceIndex
from screenmind.simplemem_adapter import SimpleMemExtractor
from screenmind.store import Store


async def check(cfg):
    root = Path("artifacts") / ("memory-live-" + uuid.uuid4().hex)
    store = Store(root / "state.db", "synthetic", memory_provider=BasicMemoryProvider(history_screen_limit=0, history_input_limit=0))
    try:
        encoder = QwenEmbedding(cfg.embedding_model_path) if cfg.embedding == "qwen" else HashDemoEmbedding()
        service = MemoryService(store, encoder, LanceIndex(root / "index", encoder.name), SimpleMemExtractor(cfg))
        wid = store.create_work({"event_id": uuid.uuid4().hex, "title": "合成场地记录", "subagent_id": "party"})[0]["id"]
        def task(text):
            sid = store.save_screen(ScreenInput(event_id=uuid.uuid4().hex, session_id=wid, app_package="com.example",
                app_name="合成测试", page_title="场地", captured_at="2026-11-09T10:00:00+08:00",
                blocks=[{"id": "venue", "text": text, "message_at": "2026-11-09T09:00:00+08:00"}]).model_dump(mode="json"))[0]["id"]
            tid = store.create_task(TaskInput(event_id=uuid.uuid4().hex, instruction="整理场地证据", work_id=wid,
                screen_ids=[sid], interaction_protocol="cloud-v1").model_dump(mode="json"))[0]["id"]
            return tid, sid
        _, source = task("场地甲只能坐6人，这次聚会有8人，因此当时没有选甲场地。")
        start = time.monotonic()
        await service.index_one()
        assert store.memory_status(wid) == {"done": 1}, store.memory_status(wid)
        build_seconds = round(time.monotonic()-start, 2)
        tid, _ = task("为什么没有选择甲场地？")
        claimed = store.claim(tid)
        assert not claimed["memory_context"]["history"]
        result = await service.enrich(claimed)
        history = json.dumps(result["memory_context"]["history"], ensure_ascii=False)
        assert source in history and "只能坐6人" in history
        assert store.work(wid)["memory_version"] == 0
        report = {"passed": True, "synthetic": True, "encoder": encoder.name, "extractor": service.extractor.name,
            "model": cfg.model, "build_seconds": build_seconds, "retrieval": result["memory_context"]["retrieval"],
            "raw_evidence_retrieved": True, "formal_state_unchanged": True, "scope": "single_source_integration_not_benchmark"}
        Path("artifacts/memory-live-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
    finally:
        store.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file")
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    if not args.run:
        parser.error("--run permits a real synthetic memory extraction")
    asyncio.run(check(Settings.from_env(args.env_file)))
