# 云端 Agent 实施路线

依据：2026-11-09 计划书 v1.2。本文件区分当前代码与后续设计；聚会是框架验证场景，功能扩展通过 SubAgent 接入。当前代码 v0.4，细节见 [新增模块实现](phase-two.md)。

## 当前实现

- FastAPI 接口、SQLite 状态与版本、事件记录、幂等和任务恢复。
- Router、插件注册表、Worker 工具循环、Information、Party 与 Schedule 三个业务能力。
- 模拟模型和兼容 Chat Completions 的真实模型适配器。
- 可修订与确认的卡片，以及适配手机宽度的网页调试台。
- v0.3 / M0：WorkResolver、基础 MemoryProvider、ContextBuilder、cloud-v1，详见 [第一阶段实现](phase-one.md)。
- v0.4：SimpleMem 文本适配与 LanceDB 检索、可选 Qwen、Schedule、限定时间检查委派、授权 ICS 导出/CalDAV；详见 [实现边界](phase-two.md) 与 [验证记录](phase-two-validation.md)。
- 当前接口以 `api-contract.md` 和运行服务的 OpenAPI 为准。

## 下一步顺序

| 阶段 | 云端交付 | 验收 |
| --- | --- | --- |
| W1—W2 | 公共交互契约、基础 MemoryProvider、端云联调 | 云端实现及合成 HTTP 联调已完成；实际手机 OCR 到卡片仍待端侧验收 |
| W3 | Party 完整闭环、独立环境研究探针 | 初始计划、成员变化、澄清、确认、实际行动反馈 |
| W4—W5 | Schedule、单层辅助委派、增强记忆适配 | v0.4 已实现并完成工程测试；实际语义收益、Qwen 权重和外部账号联调分别验收 |
| W6—W7 | 对照与冻结评测 | 短、长、冲突历史分层；计算构建与检索总成本；无稳定收益则保留基础路径 |

前两周云端预算26小时：必做22小时、研究探针2小时、缓冲2小时。研究依赖不阻塞基础链路。

## 需要逐步补齐的公共边界

1. 已实现显式 WorkResolver 与 Router 分离；自动关联留待验证。事项主能力保持稳定。
2. 已实现正式状态、原始证据、派生索引分层；增强模式默认关闭，检索收益等待冻结评测。
3. 四类交互均有执行路径；外部动作需要单独授权，并依据回执标记成功。
4. 辅助能力只接收限定来源并返回结果，由主流程统一生成候选，框架统一提交。
5. 已实现异步索引、删除代次校验、原文补齐与失败降级；已确认状态始终单独读取。

当前协议样例：[phase-one-contracts.json](../examples/phase-one-contracts.json)。原 [interaction-contract-v1.2.json](drafts/interaction-contract-v1.2.json) 保留为历史草案，`next-draft-1` 不是运行协议。旧客户端继续使用 confirm/card_version；新客户端选择 cloud-v1 并使用 responses/output_version。

## 记忆选型

SQLite 保存正式状态和证据；MemoryService 在基础上下文之上接入 SimpleMem 文本抽取、可选 Qwen3-Embedding-0.6B 与 LanceDB 索引。增强依赖独立安装；默认 basic。分别验收上游最小运行、框架适配、实际收益，不能把安装成功当作完成长期记忆能力。当前选型的实际验证状态以 phase-two-validation.md 为准。

历史验收样例：当前选定场地丙，要求系统检索先前未采用的甲、乙及当时原因，按当前人数重新分析，保留历史条件待核实的边界，不自动改选。OCR 在端侧运行；云端当前仅接收结构化输入。

## 目录整理验证

本节为开发前的目录整理记录；最新实现验证见 [phase-one-validation.md](phase-one-validation.md)。

2026-11-09 在 `Cloud Agent` 新目录运行现有回归：**31 passed，8.32秒**。使用原开发环境已安装的 Python 依赖，确认导入的是新目录的代码；测试使用 mock / HTTP MockTransport，没有调用真实模型。保留一条 Starlette TestClient 依赖弃用提示。本次未重新执行浏览器或真实模型验收。

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
python -m pytest -q --basetemp=artifacts/pytest-directory-verified --junitxml=artifacts/directory-check.xml
```
