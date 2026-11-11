"""Small frozen product experiment, NOT LoCoMo replication. Real calls require --run.

Every variant uses the same source evidence and history budget. Report memory-build
and query-plan tokens separately; no fabricated accuracy or tokenizer estimates.
"""
import argparse
import asyncio
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import time
from uuid import uuid4

from screenmind.config import Settings
from screenmind.memory import ContextBuilder, BasicMemoryProvider
from screenmind.memory_service import MemoryService
from screenmind.models import TaskInput, WorkInput
from screenmind.research_memory import StructuredExtractor, IntentPlanner, NoEmbedding, NoVectorIndex
from screenmind.simplemem_adapter import RawExtractor
from screenmind.store import Store, encode


def add_source(store, wid, text, instruction='保存证据'):
    sid=store.save_screen({'event_id':uuid4().hex,'session_id':'research','app_package':'synthetic',
        'app_name':'合成实验','page_title':'研究样例','captured_at':'2026-11-11T12:00:00+08:00',
        'blocks':[{'id':'body','text':text,'kind':'text'}]})[0]['id']
    task=store.create_task(TaskInput(event_id=uuid4().hex,work_id=wid,screen_ids=[sid],instruction=instruction).model_dump())[0]
    return task,sid+':body'


def aggregate_usage(usages):
    return {'calls':sum(u.get('calls',0) for u in usages),
            'prompt_tokens':sum(u.get('prompt_tokens') or 0 for u in usages),
            'completion_tokens':sum(u.get('completion_tokens') or 0 for u in usages),
            'missing_usage_calls':sum(not u.get('usage_reported',False) for u in usages)}


async def run(args):
    fixture=Path(args.cases)
    dataset=json.loads(fixture.read_text(encoding='utf-8'))
    cfg=Settings.from_env()
    folder=Path('artifacts')/('memory-reproduction-'+uuid4().hex[:12]);folder.mkdir(parents=True)
    report={'scope':'small_synthetic_retrieval_experiment_not_paper_benchmark',
        'dataset_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'model':cfg.model,
        'synthetic':True,'history_char_budget':12000,'semantic_vectors':False,'variants':[]}
    for mode in ['full_history','basic','raw_lexical','structured_planned']:
        baseline = BasicMemoryProvider(history_char_budget=200000, history_screen_limit=200) if mode=='full_history' else None
        store=Store(folder/(mode+'.sqlite3'),'research-owner',memory_provider=baseline)
        service=None
        if mode not in {'basic','full_history'}:
            service=MemoryService(store,NoEmbedding(),NoVectorIndex(),
                StructuredExtractor(cfg) if mode=='structured_planned' else RawExtractor(),
                IntentPlanner(cfg) if mode=='structured_planned' else None,'structured')
            store.memory_service=service
        variant={'name':mode,'results':[],'build_usage':{},'query_usage':{},'build_seconds':0,'failed_builds':0}
        works=[]
        for case in dataset['works']:
            work=store.create_work(WorkInput(event_id=uuid4().hex,title=case['name'],subagent_id='information').model_dump())[0]
            refs={}
            for fact in case['facts']:
                _,refs[fact['id']]=add_source(store,work['id'],fact['text'])
            for i in range(dataset['noise_count']):
                add_source(store,work['id'],f'准备记录第{i}条：带纸巾、矿泉水、备用电池。活动现场请保持整洁。')
            works.append((case,work,refs))
        if service:
            started=time.monotonic()
            while await service.index_one():pass
            variant['build_seconds']=round(time.monotonic()-started,2)
            usages=[json.loads(r[0]) for r in store.db.execute("SELECT payload FROM events WHERE kind='memory.model_usage'")]
            variant['build_usage']=aggregate_usage(usages)
            variant['failed_builds']=store.db.execute("SELECT count(*) FROM memory_jobs WHERE status!='done'").fetchone()[0]
        query_usage=[]
        for case,work,refs in works:
            for query in case['queries']:
                task,_=add_source(store,work['id'],'请根据已有事项材料回答问题。',query['question'])
                task=store.claim(task['id'])
                started=time.monotonic()
                if service:task=await service.enrich(task)
                context=task['memory_context'];available=ContextBuilder.source_refs(context)
                required={refs[x] for x in query['required']}
                retrieval=context.get('retrieval',{})
                if retrieval.get('planning_usage'):query_usage.append(retrieval['planning_usage'])
                variant['results'].append({'work':case['name'],'question':query['question'],
                    'required_sources':len(required),'recalled_sources':len(required&available),
                    'all_required_present':required.issubset(available),'history_chars':context['history_chars_used'],
                    'query_seconds':round(time.monotonic()-started,2),'planning_error':retrieval.get('planning_error')})
                print(mode,case['name'],len(required&available),'/',len(required),flush=True)
        variant['query_usage']=aggregate_usage(query_usage)
        variant['source_recall']=sum(r['recalled_sources'] for r in variant['results'])/sum(r['required_sources'] for r in variant['results'])
        variant['all_required_rate']=sum(r['all_required_present'] for r in variant['results'])/len(variant['results'])
        report['variants'].append(variant)
        store.close()
        (folder/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    report['limitations']=['4 synthetic questions; no held-out human data','Measures evidence recall, not answer accuracy',
        'No semantic embeddings; no LoCoMo scores','Source-window consolidation only; no cross-source semantic synthesis',
        'Full-history control has a separate 200000-character budget; other variants have 12000',
        'Latency includes model/network variance; no production capacity claim']
    (folder/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('REPORT',folder/'report.json',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--run',action='store_true')
    parser.add_argument('--cases',default='examples/research-memory-cases.json')
    args=parser.parse_args()
    if not args.run:parser.error('--run permits synthetic real model calls')
    asyncio.run(run(args))
