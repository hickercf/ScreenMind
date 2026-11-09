"""Explicit optional weight download and real CPU embedding check (about 1.2 GB weights)."""
import argparse
import json
from pathlib import Path
import time

from huggingface_hub import snapshot_download


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="artifacts/models/Qwen3-Embedding-0.6B")
    parser.add_argument("--revision", default="97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3")
    args = parser.parse_args()
    root = Path(args.output).resolve()
    model = "Qwen/Qwen3-Embedding-0.6B"
    revision = args.revision
    print(json.dumps({"model": model, "revision": revision, "stage": "download"}), flush=True)
    snapshot_download(model, revision=revision, local_dir=root, max_workers=2,
        allow_patterns=["config.json", "modules.json", "sentence_bert_config.json", "config_sentence_transformers.json",
                        "1_Pooling/*", "tokenizer*", "vocab.json", "merges.txt", "model.safetensors"])
    from screenmind.retrieval import QwenEmbedding
    started = time.monotonic()
    encoder = QwenEmbedding(str(root))
    vectors = encoder.encode(["场地甲只有六个座位，未被选择。", "明天要准备答辩材料。"])
    query = encoder.encode(["为什么没有选择甲场地？"], query=True)[0]
    scores = [sum(a*b for a, b in zip(query, v)) for v in vectors]
    report = {"model": model, "revision": revision, "path": str(root), "dimension": len(query),
              "scores": scores, "load_and_encode_seconds": round(time.monotonic()-started, 2)}
    assert len(query) == 1024 and scores[0] > scores[1], report
    Path("artifacts/qwen-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
