"""Replaceable encoders and per-owner/work LanceDB indexes; no canonical state here."""
import hashlib
import math
import re
from pathlib import Path


def terms(text):
    text = text.casefold()
    tokens = re.findall(r"[a-z0-9_.:-]+", text)
    for run in re.findall(r"[\u3400-\u9fff]+", text):
        tokens.extend(run)
        tokens.extend(run[i:i+2] for i in range(len(run)-1))
    return set(tokens)


class HashDemoEmbedding:
    """Deterministic lexical vectors for offline engineering tests, NOT semantic Qwen."""
    name = "hash-demo-256"
    dimension = 256

    def encode(self, texts, query=False):
        vectors = []
        for text in texts:
            vector = [0.] * self.dimension
            for token in terms(text):
                digest = hashlib.sha256(token.encode()).digest()
                vector[int.from_bytes(digest[:4], "big") % self.dimension] += 1 if digest[4] % 2 else -1
            norm = math.sqrt(sum(x*x for x in vector)) or 1
            vectors.append([x/norm for x in vector])
        return vectors


class QwenEmbedding:
    name = "Qwen/Qwen3-Embedding-0.6B"
    dimension = 1024

    def __init__(self, model_path=None):
        # Explicit local weights; service startup never silently downloads a model.
        if not model_path or not Path(model_path).is_dir():
            raise ValueError("Configure a local Qwen embedding model directory")
        from sentence_transformers import SentenceTransformer
        self.model = SentenceTransformer(model_path, local_files_only=True, trust_remote_code=False)
        self.model.max_seq_length = 4096
        if self.model.get_sentence_embedding_dimension() != self.dimension:
            raise ValueError("Unexpected embedding dimension")

    def encode(self, texts, query=False):
        if query:
            texts = ["Instruct: Retrieve historical evidence relevant to this task.\nQuery: " + t for t in texts]
        return self.model.encode(texts, normalize_embeddings=True, show_progress_bar=False).tolist()


class LanceIndex:
    def __init__(self, path, encoder_name):
        import lancedb
        self.db = lancedb.connect(str(path))
        self.encoder_name = encoder_name

    def table_name(self, owner, work_id):
        return "memory_" + hashlib.sha256((owner + "\0" + work_id + "\0" + self.encoder_name).encode()).hexdigest()[:32]

    def open(self, name):
        try:
            return self.db.open_table(name)
        except (FileNotFoundError, ValueError) as exc:
            if isinstance(exc, FileNotFoundError) or "was not found" in str(exc):
                return None
            raise

    def put(self, owner, work_id, ref, vector):
        name = self.table_name(owner, work_id)
        row = {"id": hashlib.sha256(ref.encode()).hexdigest(), "source_ref": ref, "vector": vector}
        table = self.open(name)
        if table is None:
            self.db.create_table(name, data=[row])
            return
        table.merge_insert("id").when_matched_update_all().when_not_matched_insert_all().execute([row])

    def search(self, owner, work_id, vector, limit=12):
        table = self.open(self.table_name(owner, work_id))
        if table is None:
            return []
        return [r["source_ref"] for r in table.search(vector).limit(limit).to_list()]

    def delete(self, owner, work_id, ref):
        table = self.open(self.table_name(owner, work_id))
        if table is None:
            return
        # Only generated hex is interpolated, never user input.
        table.delete("id = '" + hashlib.sha256(ref.encode()).hexdigest() + "'")
