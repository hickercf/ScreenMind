"""Frozen local CPU comparison. No remote model calls, training, or production writes.

Run lexical and qwen in separate processes using identical fixture bytes. Windows
memory reports include total process working set/private commit, not weights alone.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import statistics
import threading
import time

from screenmind.retrieval import lexical_scores, terms


class ProcessMemory(ctypes.Structure):
    _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
        (name, ctypes.c_size_t) for name in ('PeakWorkingSetSize', 'WorkingSetSize',
        'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
        'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage', 'PrivateUsage')]


class SystemMemory(ctypes.Structure):
    _fields_ = [('dwLength', wintypes.DWORD), ('dwMemoryLoad', wintypes.DWORD)] + [
        (name, ctypes.c_ulonglong) for name in ('ullTotalPhys', 'ullAvailPhys',
        'ullTotalPageFile', 'ullAvailPageFile', 'ullTotalVirtual', 'ullAvailVirtual', 'ullAvailExtendedVirtual')]


def memory():
    if os.name != 'nt':
        raise RuntimeError('This measurement implementation requires Windows')
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    psapi = ctypes.WinDLL('psapi', use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessMemory), wintypes.DWORD]
    p = ProcessMemory(); p.cb = ctypes.sizeof(p)
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(p), p.cb):
        raise ctypes.WinError(ctypes.get_last_error())
    s = SystemMemory(); s.dwLength = ctypes.sizeof(s)
    if not kernel.GlobalMemoryStatusEx(ctypes.byref(s)):
        raise ctypes.WinError(ctypes.get_last_error())
    return {'rss_bytes': p.WorkingSetSize, 'private_bytes': p.PrivateUsage,
            'os_peak_rss_bytes': p.PeakWorkingSetSize, 'os_peak_private_bytes': p.PeakPagefileUsage,
            'system_total_bytes': s.ullTotalPhys, 'system_available_bytes': s.ullAvailPhys}


class Monitor:
    def __init__(self):
        self.stop = threading.Event()
        self.samples = []
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.wait(.05):
            self.samples.append(memory())

    def __enter__(self):
        self.samples.append(memory()); self.thread.start(); return self

    def __exit__(self, *args):
        self.stop.set(); self.thread.join(); self.samples.append(memory())

    def result(self):
        return {'sample_interval_ms': 50, 'samples': len(self.samples),
                'peak_rss_bytes': max(x['os_peak_rss_bytes'] for x in self.samples),
                'peak_private_bytes': max(x['os_peak_private_bytes'] for x in self.samples),
                'min_system_available_bytes': min(x['system_available_bytes'] for x in self.samples)}


def fixture(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    docs = data['documents'][:]
    for work in ('party', 'schedule', 'trip'):
        for i, text in enumerate(data['noise_texts'][:data['noise_per_work']]):
            docs.append({'id': f'{work}_noise_{i:02}', 'owner': 'alice', 'work': work, 'text': text})
    mapping = {d['id']: d for d in docs}
    assert len(mapping) == len(docs)
    for q in data['queries']:
        groups = q.get('alternative_groups', [[r] for r in q['required']])
        for group in groups:
            assert group
            for ref in group:
                assert (mapping[ref]['owner'], mapping[ref]['work']) == (q['owner'], q['work'])
    return data, docs


def lexical(docs, question):
    weights = lexical_scores({d['id']: d['text'] for d in docs}, terms(question))
    return sorted((r for r in weights if weights[r] > 0), key=lambda r: (-weights[r], r))


def fusion(semantic, lexical_ranking):
    scores = {}
    for ranking in (semantic[:12], lexical_ranking[:12]):
        for rank, ref in enumerate(ranking, 1):
            scores[ref] = scores.get(ref, 0) + 1/(60+rank)
    return sorted(scores, key=lambda ref: (-scores[ref], ref))


def metrics(rows):
    answerable = [r for r in rows if r['groups']]
    result = {'questions': len(answerable)}
    for k in (1, 3, 5, 8):
        hits = sum(sum(bool(set(g)&set(r['ranking'][:k])) for g in r['groups']) for r in answerable)
        total = sum(len(r['groups']) for r in answerable)
        all_hit = sum(all(set(g)&set(r['ranking'][:k]) for g in r['groups']) for r in answerable)
        result[f'recall@{k}'] = hits/total
        result[f'all_evidence@{k}'] = all_hit/len(answerable)
        result[f'all_evidence_count@{k}'] = all_hit
    result['mrr'] = statistics.mean(next((1/i for i, ref in enumerate(r['ranking'], 1)
        if any(ref in g for g in r['groups'])), 0) for r in answerable)
    return result


def latency(values):
    values = sorted(values)
    return {'n': len(values), 'median_ms': statistics.median(values),
            'p95_ms': values[min(len(values)-1, int(.95*len(values)))], 'mean_ms': statistics.mean(values)}


def run(args):
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.environ['TOKENIZERS_PARALLELISM'] = 'false'
    data, docs = fixture(Path(args.cases))
    out = Path(args.output); out.mkdir(parents=True, exist_ok=True)
    report = {'variant': args.variant, 'synthetic': True,
              'dataset_sha256': hashlib.sha256(Path(args.cases).read_bytes()).hexdigest(),
              'documents': len(docs), 'queries': len(data['queries']), 'device': 'cpu',
              'python': platform.python_version(), 'platform': platform.platform(),
              'threads': args.threads, 'rounds': args.rounds, 'before': memory(), 'rows': [],
              'protocol': 'Per-owner/work prefilter; same texts; no recent fallback; no generation; no result-driven tuning'}
    candidate_sets = {q['id']: [d for d in docs if (d['owner'], d['work']) == (q['owner'], q['work'])]
                      for q in data['queries']}
    with Monitor() as monitor:
        if args.variant == 'qwen':
            start = time.perf_counter()
            import numpy as np
            import torch
            torch.set_num_threads(args.threads)
            torch.set_num_interop_threads(1)
            from screenmind.retrieval import QwenEmbedding
            report['after_import'] = memory()
            encoder = QwenEmbedding(args.model)
            assert encoder.model.device.type == 'cpu'
            report['load_seconds'] = time.perf_counter()-start
            report['after_load'] = memory()
            report['dtype'] = str(next(encoder.model.parameters()).dtype)
            report['parameters'] = sum(p.numel() for p in encoder.model.parameters())
            report['packages'] = {p: importlib.metadata.version(p) for p in ('torch', 'transformers', 'sentence-transformers', 'numpy')}
            report['model_verification'] = json.loads((Path(args.model)/'weight-verification.json').read_text())
            report['max_seq_length'] = encoder.model.max_seq_length
            report['document_batch_size'] = 4
            report['query_instruction'] = 'Instruct: Retrieve historical evidence relevant to this task.\nQuery: '
            print(json.dumps({'stage':'loaded', 'seconds':round(report['load_seconds'],2), 'dtype':report['dtype']}), flush=True)
            start = time.perf_counter(); vectors = []
            for i in range(0, len(docs), 4):
                vectors.extend(encoder.encode([d['text'] for d in docs[i:i+4]]))
                if i % 16 == 0: print(json.dumps({'stage':'index', 'done':min(i+4,len(docs)), 'total':len(docs)}), flush=True)
            matrix = np.array(vectors, dtype=np.float32)
            assert matrix.shape == (len(docs), 1024) and np.isfinite(matrix).all()
            assert np.allclose(np.linalg.norm(matrix, axis=1), 1, atol=.001)
            report['index_build_seconds'] = time.perf_counter()-start
            report['vector_bytes'] = matrix.nbytes
            report['after_index'] = memory()
            positions = {d['id']: i for i, d in enumerate(docs)}
            np.save(out/'document-vectors.npy', matrix)
            # One explicit warm-up excluded from repeated timings; same instruction.
            warm = time.perf_counter(); encoder.encode([data['queries'][0]['text']], query=True)
            report['warmup_query_seconds'] = time.perf_counter()-warm
        timings = []; lexical_timings = []; hybrid_timings = []
        for repeat in range(args.rounds):
            for q in data['queries']:
                candidates = candidate_sets[q['id']]
                start = time.perf_counter()
                lex = lexical(candidates, q['text'])
                lex_ms = (time.perf_counter()-start)*1000
                if args.variant == 'qwen':
                    start = time.perf_counter()
                    vector = np.array(encoder.encode([q['text']], query=True)[0], dtype=np.float32)
                    indices = [positions[d['id']] for d in candidates]
                    scores = matrix[indices] @ vector
                    ranking = [candidates[i]['id'] for i in sorted(range(len(candidates)), key=lambda i: (-float(scores[i]), candidates[i]['id']))]
                    elapsed = (time.perf_counter()-start)*1000
                    fuse_start = time.perf_counter(); hybrid = fusion(ranking, lex)
                    hybrid_timings.append(elapsed+lex_ms+(time.perf_counter()-fuse_start)*1000)
                    lexical_timings.append(lex_ms)
                else:
                    ranking = lex; elapsed = lex_ms
                timings.append(elapsed)
                assert all(r in {d['id'] for d in candidates} for r in ranking)
                if repeat == 0:
                    row = {**q, 'groups': q.get('alternative_groups', [[r] for r in q['required']]),
                           'ranking': ranking, 'first_ms': elapsed, 'candidates':len(candidates)}
                    if args.variant == 'qwen':
                        row.update(hybrid_ranking=hybrid, lexical_ranking=lex,
                                   top_scores=[{'id': candidates[i]['id'], 'cosine':float(scores[i])}
                                               for i in sorted(range(len(scores)), key=lambda i:-scores[i])[:5]])
                    report['rows'].append(row)
                if q['id'] in ('q08','q16','q24','q32','u04'):
                    print(json.dumps({'stage':'queries','repeat':repeat+1,'query':q['id'],'ms':round(elapsed,1)}), flush=True)
            (out/(args.variant+'-partial.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        report['latency'] = latency(timings)
        report['metrics'] = metrics(report['rows'])
        report['by_category'] = {cat:metrics([r for r in report['rows'] if r['category']==cat])
                                 for cat in sorted({r['category'] for r in report['rows'] if r['groups']})}
        if args.variant == 'qwen':
            report['hybrid_metrics'] = metrics([{**r, 'ranking': r['hybrid_ranking']} for r in report['rows']])
            report['hybrid_latency'] = latency(hybrid_timings)
            report['lexical_latency_same_process'] = latency(lexical_timings)
        report['after_queries'] = memory()
    report['memory'] = monitor.result()
    report['limitations'] = ['Synthetic authored labels, not held-out human relevance judgments',
        'Quality evaluation uses 32 answerable questions; 4 unanswerable queries are diagnostic only',
        'No threshold calibrated for abstention; top-k cannot prove an answer exists',
        'No end-to-end answer evaluation; no LoCoMo or MTEB reproduction',
        'One Windows CPU machine; process measurements do not predict Ubuntu/server latency',
        'OS file cache not cleared; background applications may affect memory/latency',
        'Memory includes Python/framework; unquantized model; no multiuser load test',
        'Scope isolation enforced by candidate prefilter, not learned by embedding model',
        'Vectors use exact cosine search over a small corpus; no LanceDB/storage benchmark']
    target = out/(args.variant+'.json')
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'report':str(target),'metrics':report['metrics'],'latency':report['latency'], 'memory':report['memory']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', required=True, choices=['lexical','qwen'])
    parser.add_argument('--cases', default='examples/retrieval-comparison-v1.json')
    parser.add_argument('--model', default='artifacts/models/Qwen3-Embedding-0.6B')
    parser.add_argument('--output', default='artifacts/qwen-benchmark-v1')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--threads', type=int, default=4)
    run(parser.parse_args())
