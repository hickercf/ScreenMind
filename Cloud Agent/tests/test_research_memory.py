import asyncio
from dataclasses import replace
import json
import time
import pytest
from fastapi.testclient import TestClient

from screenmind.api import create_app
from screenmind.config import Settings
from screenmind.delegation import DelegationService
from screenmind.evidence_review import EvidenceReviewAgent, ReviewInput, review_packet
from screenmind.research_memory import StructuredExtractor, IntentPlanner, NoEmbedding, NoVectorIndex
from screenmind.simplemem_adapter import RawExtractor
from screenmind.memory_service import MemoryService
from screenmind.store import Store, encode
from screenmind.agents.base import ToolError, tool_call
from screenmind.providers import MockProvider
from test_engine import settings, screen, wait_task
from test_phase_one import uid, make_work, make_task
from test_phase_two import delegation_fixture, schedule_work, schedule_task


class FakeClient:
    last_usage = {'calls': 1, 'prompt_tokens': 10, 'completion_tokens': 10, 'usage_reported': True}
    def __init__(self, result):
        self.result, self.messages = result, []
    def chat_completion(self, messages, **kwargs):
        self.messages.append(messages)
        return json.dumps(self.result, ensure_ascii=False)


def test_structured_memory_preserves_quote_uncertainty_and_source_time():
    text = '小林可能来不了，还没有最终决定。'
    unit = {'statement': text, 'quote': text, 'uncertain': True, 'entities': ['小林']}
    extractor = StructuredExtractor(Settings(), FakeClient({'units': [unit, unit]}))
    result = extractor.extract(text, None, '小林')
    assert len(result['units']) == 1 and result['units'][0]['uncertain']
    assert result['message_at'] is None and result['acceptance']=='derived_hint_not_confirmed_state'
    extractor.client = FakeClient({'units': [{**unit, 'quote': '小林确定退出'}]})
    with pytest.raises(ValueError): extractor.extract(text)


def test_plan_rejects_unbounded_or_invalid_model_output():
    with pytest.raises(ValueError):
        IntentPlanner(Settings(), FakeClient({'queries': ['x'], 'top_k': 1000})).plan('test')
    with pytest.raises(ValueError):
        IntentPlanner(Settings(), FakeClient({'queries': ['x'*201]})).plan('test')


def test_failed_extraction_records_model_usage_before_retry(tmp_path):
    store=Store(tmp_path/'usage.sqlite3','owner')
    extractor=StructuredExtractor(Settings(),FakeClient({'units':[]}))
    service=MemoryService(store,NoEmbedding(),NoVectorIndex(),extractor,mode='structured')
    try:
        w=make_work(store)
        make_task(store,w['id'],'小林可能缺席')
        asyncio.run(service.index_one())
        usage=json.loads(store.db.execute("SELECT payload FROM events WHERE kind='memory.model_usage'").fetchone()[0])
        assert usage['calls']==1 and usage['succeeded'] is False
        assert store.memory_status(w['id'])=={'pending':1}
    finally:store.close()


def test_structured_retrieval_scoped_fallback_and_budget(tmp_path):
    store=Store(tmp_path/'memory.sqlite3','owner')
    class BrokenPlan:
        def plan(self, query): raise ValueError('unavailable')
    service=MemoryService(store,NoEmbedding(),NoVectorIndex(),RawExtractor(),BrokenPlan(),'structured')
    store.memory_service=service
    try:
        work, other=make_work(store),make_work(store)
        _,old=make_task(store,work['id'],'甲场地只有六个座位，因此八人的聚会没有选择它。')
        for i in range(14): make_task(store,work['id'],f'杂项{i}：记得带纸巾和水。')
        _,secret=make_task(store,other['id'],'甲场地跨事项秘密')
        async def build():
            while await service.index_one(): pass
        asyncio.run(build())
        task,_=make_task(store,work['id'],'为什么没有选甲场地？')
        task=store.claim(task['id'])
        enriched=asyncio.run(service.enrich(task))['memory_context']
        assert old['id'] in encode(enriched['history']) and secret['id'] not in encode(enriched)
        assert enriched['mode']=='structured-degraded'
        assert enriched['retrieval']['semantic_vectors'] is False
        assert enriched['history_chars_used']<=enriched['history_char_budget']
        store.delete_screen(old['id'])
        assert store.task(task['id'])['status']=='failed'
        assert store.work(work['id'])['memory_version']==0
    finally: store.close()


def test_memory_api_pagination_deletion_and_work_scope(settings):
    with TestClient(create_app(replace(settings,memory_mode='structured'))) as c:
        store=c.app.state.store
        w=c.portal.call(make_work,store)
        _,source=c.portal.call(make_task,store,w['id'],'小林负责蛋糕')
        endpoint=f"/api/works/{w['id']}/memories"
        for _ in range(100):
            result=c.get(endpoint).json()
            if result['entries']: break
            time.sleep(.02)
        assert result['enabled'] and result['entries']
        assert c.get(endpoint+'?limit=0').status_code==422
        assert c.get('/api/works/nonexistent/memories').status_code==404
        c.delete('/api/screens/'+source['id'])
        assert c.get(endpoint).json()['entries']==[]


def test_review_helper_cannot_cite_outside_packet_or_write_state():
    packet={'question':'谁负责蛋糕？','records':{'a':{'text':'小林负责蛋糕','authority':'untrusted_evidence'}}}
    good={'findings':[{'statement':'小林负责蛋糕','citations':[{'source_ref':'a','quote':'小林负责蛋糕'}]}],'missing_information':[]}
    helper=EvidenceReviewAgent(Settings(),FakeClient(good))
    assert helper.review(packet)['read_only']
    for bad in [
        {**good,'state':{'approved':True}},
        {'findings':[{'statement':'泄漏','citations':[{'source_ref':'other','quote':'小林'}]}],'missing_information':[]},
        {'findings':[{'statement':'无依据','citations':[{'source_ref':'a','quote':'小李负责蛋糕'}]}],'missing_information':[]}]:
        helper.client=FakeClient(bad)
        with pytest.raises(ValueError): helper.review(packet)


def test_review_delegation_cache_scope_and_stale_results(tmp_path):
    store=Store(tmp_path/'delegate.sqlite3','owner')
    class Helper:
        calls=0
        async def run(self, packet):
            self.calls+=1
            assert len(packet['records'])==1
            return {'read_only':True,'scope':'provided_evidence_only','findings':[],'missing_information':[]}
    try:
        task,args=delegation_fixture(store)
        ref=args['candidate']['source_refs'][0]
        payload={'question':'核对历史','source_refs':[ref]}
        helper=Helper();service=DelegationService(reviewer=helper)
        first=asyncio.run(service.run(store,task,payload,'evidence_review'))
        assert asyncio.run(service.run(store,task,payload,'evidence_review'))==first and helper.calls==1
        with pytest.raises(ToolError):
            asyncio.run(service.run(store,task,{**payload,'source_refs':['other:secret']},'evidence_review'))
        assert store.work(task['work_id'])['memory_version']==0
        class Stale:
            async def run(self, packet):
                store.db.execute('UPDATE works SET memory_version=memory_version+1 WHERE id=?',(task['work_id'],))
                return {'findings':[]}
        service.reviewer=Stale()
        with pytest.raises(ToolError):
            asyncio.run(service.run(store,task,{**payload,'question':'第二次核对'},'evidence_review'))
        assert store.db.execute("SELECT count(*) FROM delegations WHERE status='discarded'").fetchone()[0]==1
    finally: store.close()


def test_worker_model_delegation_is_advisory(settings):
    class Reviewer:
        async def run(self, packet): return {'findings':[],'missing_information':[],'read_only':True}
    class Delegating(MockProvider):
        async def complete(self,messages,tools):
            read=next((m for m in messages if m.get('name')=='read_screens'),None)
            if read and not any(m.get('name')=='delegate_evidence_review' for m in messages):
                refs=[b['source_ref'] for s in json.loads(read['content'])['screens'] for b in s['blocks']]
                return tool_call('delegate_evidence_review',{'question':'核对日程依据','source_refs':refs})
            return await super().complete(messages,tools)
    cfg=replace(settings,evidence_review=True,model='fake',base_url='https://example.com/v1',model_key='test-only')
    with TestClient(create_app(cfg,provider=Delegating(.001))) as c:
        c.app.state.runtime.worker.delegation.reviewer=Reviewer()
        work=schedule_work(c)
        task,_=schedule_task(c,work['id'],[{'id':'x','title':'组会','at':'2026-12-10T17:00:00+08:00'}])
        final=wait_task(c,task['id'])
        assert final['interaction']['kind']=='state_proposal'
        assert c.get(f"/api/works/{work['id']}").json()['work']['memory_version']==0
        trace=c.get(f"/api/tasks/{task['id']}/delegations").json()['delegations']
        assert trace[0]['helper']=='evidence_review' and trace[0]['status']=='succeeded'
