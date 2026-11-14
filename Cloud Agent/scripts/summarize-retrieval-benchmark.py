"""Produce a compact, auditable comparison from completed isolated-process runs."""
import argparse
import hashlib
import json
from pathlib import Path


def summarize(folder):
    lex = json.loads((folder/'lexical.json').read_text(encoding='utf-8'))
    qwen = json.loads((folder/'qwen.json').read_text(encoding='utf-8'))
    assert lex['dataset_sha256'] == qwen['dataset_sha256']
    assert lex['documents'] == qwen['documents'] and lex['queries'] == qwen['queries']
    assert [r['id'] for r in lex['rows']] == [r['id'] for r in qwen['rows']]
    comparison = {'dataset_sha256': lex['dataset_sha256'], 'documents':lex['documents'],
        'answerable_queries':32, 'unanswerable_diagnostics':4, 'synthetic':True,
        'hardware':json.loads((folder/'hardware.json').read_text(encoding='utf-8-sig')),
        'model':qwen['model_verification'], 'dtype':qwen['dtype'], 'threads':qwen['threads'],
        'rounds':qwen['rounds'], 'packages':qwen['packages'],
        'variants':{
            'lexical': {'metrics':lex['metrics'],'latency':lex['latency'],'memory':lex['memory']},
            'qwen': {'metrics':qwen['metrics'],'latency':qwen['latency'],'memory':qwen['memory']},
            'hybrid_rrf': {'metrics':qwen['hybrid_metrics'],'latency':qwen['hybrid_latency'],
                           'memory_note':'Same Qwen process; no separate hybrid memory measurement'}},
        'load_seconds':qwen['load_seconds'], 'index_build_seconds':qwen['index_build_seconds'],
        'vector_bytes':qwen['vector_bytes'], 'memory_snapshots':{
            name:qwen[name] for name in ('before','after_import','after_load','after_index','after_queries')},
        'by_category': {'lexical':lex['by_category'], 'qwen':qwen['by_category']},
        'rows':[], 'limitations':qwen['limitations']}
    for l, q in zip(lex['rows'],qwen['rows']):
        row = {'id':q['id'], 'text':q['text'], 'category':q['category'], 'groups':q['groups'],
               'lexical_top8':l['ranking'][:8], 'qwen_top8':q['ranking'][:8],
               'hybrid_top8':q['hybrid_ranking'][:8], 'qwen_top_scores':q['top_scores']}
        for k in (1,3,5,8):
            for method, ranking in [('lexical',l['ranking']),('qwen',q['ranking']),('hybrid',q['hybrid_ranking'])]:
                row[f'{method}_all@{k}'] = (all(set(g)&set(ranking[:k]) for g in q['groups']) if q['groups'] else None)
        comparison['rows'].append(row)
    for p in ('lexical.json','qwen.json'):
        comparison[p+'_sha256'] = hashlib.sha256((folder/p).read_bytes()).hexdigest()
    (folder/'comparison.json').write_text(json.dumps(comparison,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in comparison.items() if k not in ('rows','limitations','by_category')},ensure_ascii=True,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--folder',type=Path,default=Path('artifacts/qwen-benchmark-v1'))
    summarize(parser.parse_args().folder)
