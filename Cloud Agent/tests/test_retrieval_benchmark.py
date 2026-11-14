"""Verify benchmark scoring and isolation independently of Qwen weights."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('retrieval_benchmark', ROOT/'scripts/benchmark-retrieval.py')
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def test_metrics_count_alternatives_once_and_exclude_unanswerable():
    rows = [
        {'groups': [['a', 'b'], ['c']], 'ranking': ['b', 'x', 'c']},
        {'groups': [['d']], 'ranking': ['x', 'd']},
        {'groups': [], 'ranking': ['a']},
    ]
    result = benchmark.metrics(rows)
    assert result['questions'] == 2
    assert result['recall@1'] == 1/3
    assert result['all_evidence_count@1'] == 0
    assert result['all_evidence_count@3'] == 2
    assert result['mrr'] == .75


def test_fixture_labels_authorized_and_foreign_decoys_excluded():
    data, docs = benchmark.fixture(ROOT/'examples/retrieval-comparison-v1.json')
    assert len(data['queries']) == 36
    assert len(docs) == 83
    for query in data['queries']:
        scoped = [d for d in docs if (d['owner'], d['work']) == (query['owner'], query['work'])]
        ranking = benchmark.lexical(scoped, query['text'])
        assert not {'x01', 'x02', 'x03'} & set(ranking)
        assert set(query['required']).issubset({d['id'] for d in scoped})


def test_fusion_is_bounded_and_rewards_agreement():
    semantic = ['shared'] + [f's{i}' for i in range(20)]
    lexical = ['first', 'shared'] + [f'l{i}' for i in range(20)]
    ranking = benchmark.fusion(semantic, lexical)
    assert ranking[0] == 'shared'
    assert set(ranking) == set(semantic[:12]) | set(lexical[:12])
