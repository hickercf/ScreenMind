# 云端 Agent 实施路线

依据：2026-11-09 计划书 v1.2。本文件区分当前代码与后续设计；聚会是框架验证场景，功能扩展通过 SubAgent 接入。

## 当前实现

- FastAPI 接口、SQLite 状态与版本、事件记录、幂等和任务恢复。
- Router、插件注册表、Worker 工具循环、Information 与 Party 两个业务能力。
- 模拟模型和兼容 Chat Completions 的真实模型适配器。
- 可修订与确认的卡片，以及适配手机宽度的网页调试台。
- 当前接口以 `api-contract.md` 和运行服务的 OpenAPI 为准。

## 下一步顺序

| 阶段 | 云端交付 | 验收 |
| --- | --- | --- |
| W1—W2 | 公共交互契约、基础 MemoryProvider、端云联调 | 不依赖向量服务返回正式快照、授权近期原文与来源；实际 OCR 到卡片 |
| W3 | Party 完整闭环、独立环境研究探针 | 初始计划、成员变化、澄清、确认、实际行动反馈 |
| W4—W5 | Schedule、单层辅助委派、增强记忆适配 | 改期冲突和三个异常；辅助能力无直接状态写权限；Party 回归 |
| W6—W7 | 对照与冻结评测 | 短、长、冲突历史分层；计算构建与检索总成本；无稳定收益则保留基础路径 |

前两周云端预算26小时：必做22小时、研究探针2小时、缓冲2小时。研究依赖不阻塞基础链路。

## 需要逐步补齐的公共边界

1. WorkResolver 判断材料属于哪个事项，Router 判断由哪个能力处理。事项主能力保持稳定。
2. 正式状态、原始证据与派生记忆分层；按事项隔离，确认和版本校验后提交正式状态。
3. 信息结果、澄清、状态提议、外部动作提议分别定义回应与完成语义。
4. 辅助能力只接收限定来源并返回结果，由主流程统一生成候选，框架统一提交。
5. 材料落库后可异步构建索引，确认后刷新标记；删除、未索引材料和失败降级需验证。

拟议协议样例：[interaction-contract-v1.2.json](drafts/interaction-contract-v1.2.json)。其中 `next-draft-1`、`output_version` 等为后续协议设计；当前服务仍使用现有 `confirm`、`card_version` 等接口，不能直接把样例发送给当前 API。

## 记忆选型

SQLite 保存正式状态和证据；后续通过 MemoryProvider 适配 SimpleMem 文本机制、Qwen3-Embedding-0.6B 和 LanceDB 派生索引。三者尚未纳入当前运行依赖。分别验收上游最小运行、框架适配、实际收益，不能把安装成功当作完成长期记忆能力。

历史验收样例：当前选定场地丙，要求系统检索先前未采用的甲、乙及当时原因，按当前人数重新分析，保留历史条件待核实的边界，不自动改选。OCR 在端侧运行；云端当前仅接收结构化输入。

## 目录整理验证

2026-11-09 在 `Cloud Agent` 新目录运行现有回归：**31 passed，8.32秒**。使用原开发环境已安装的 Python 依赖，确认导入的是新目录的代码；测试使用 mock / HTTP MockTransport，没有调用真实模型。保留一条 Starlette TestClient 依赖弃用提示。本次未重新执行浏览器或真实模型验收。

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
python -m pytest -q --basetemp=artifacts/pytest-directory-verified --junitxml=artifacts/directory-check.xml
```
