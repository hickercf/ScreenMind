"""One synthetic extraction using the configured real model. Explicit --run required."""
import argparse
import json
from pathlib import Path
import time

from screenmind.config import Settings
from screenmind.simplemem_adapter import SimpleMemExtractor

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file")
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    if not args.run:
        parser.error("--run explicitly permits one synthetic model extraction")
    settings = Settings.from_env(args.env_file)
    extractor = SimpleMemExtractor(settings)
    start = time.monotonic()
    result = extractor.extract("小王说：场地甲只能坐6人，我们有8人，因此这次没有选择场地甲。", "2026-11-09T12:00:00+08:00", "小王")
    assert result["text"] and result["keywords"]
    report = {"synthetic": True, "extractor": extractor.name, "model": settings.model,
              "result": result, "elapsed_seconds": round(time.monotonic()-start, 2),
              "scope": "single_text_extraction_not_benchmark"}
    Path("artifacts").mkdir(exist_ok=True)
    Path("artifacts/simplemem-live-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
