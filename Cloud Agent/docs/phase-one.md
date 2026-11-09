# 云端第一阶段：M0 基础框架（v0.3）

这是 M0 的历史阶段说明。v0.4 已继续实现增强记忆、Schedule、辅助委派和外部动作，当前运行边界见 [phase-two.md](phase-two.md)。

本阶段落实计划书 v1.2 的 W1—W2 云端内容。当前实现可用于手机端联调；合成 HTTP 联调不等同于 Android 真机 OCR 验收。

验证记录：[43项回归及两轮实际 HTTP 联调](phase-one-validation.md)。

## 运行与兼容

在 `Cloud Agent` 目录按 README 安装依赖并运行。旧客户端省略 `interaction_protocol` 时继续使用 `legacy`：信息卡等待 `confirm`，字段修订与 Party 流程保持原行为。数据库采用新增表/列迁移，不清空原任务和事项。

新客户端创建任务时传 `interaction_protocol: "cloud-v1"`。`GET /api/contracts` 返回协议版本、输出 Schema 和实际启用能力；OpenAPI 描述回应联合类型。`next-draft-1` 文件是历史设计，不能直接作为运行接口使用。

## 事项关联与能力路由

`POST /api/works/resolve` 接受 `screen_ids` 和可选 `work_id`。该接口只检查与返回候选，不绑定材料、不创建任务、不调用模型。

- 显式指定可访问事项且材料不冲突：`selected`。
- 材料已有事项绑定但未选择：`needs_selection`，返回候选 ID；创建任务前必须选择。
- 未绑定材料：`unassigned`，允许一次性信息任务或由用户创建事项。
- 材料跨事项、事项不属于当前工作区或材料已用于不可重新绑定的独立任务：拒绝。

Router 在事项解析后选能力。已有事项保持绑定的 SubAgent，不能借一次调用切换主能力。没有语义自动关联或多 Agent 委派执行。

## 基础记忆

`BasicMemoryProvider` 在任务开始执行的同一事务内建立快照，`ContextBuilder` 提供统一工具输入与引用白名单。快照保存在任务详情的 `memory_context`，包含 `task_id`、`revision`、`work_id`、正式状态 `memory`、本轮 `screens`、本任务用户修订和 `history`。

- 正式状态读取已确认快照，并保持现有 `memory_base` 乐观版本校验。版本0表示还没有正式状态提交。
- 历史只来自该事项已绑定屏幕和其他任务的用户修订；不访问别的事项、工作区或未关联材料。无事项任务不检索其他任务。
- 默认最多10份历史屏幕、20条历史修订，总历史内容预算12000字符；候选查询有上限，超限保留完整来源记录而不截断原文。`history_omitted` 表明有材料未纳入，不保证穷尽历史。
- 保留屏幕采集时间、来源、坐标和说话人。历史用户修订标记为历史证据，不作为当前新指令。材料存在不等于内容已经被采纳。
- 屏幕引用使用 `screen_id:block_id`，用户修订使用 `input:task_id:revision`，正式状态沿用 `memory:work_id:version`。只允许引用本轮实际载入的来源。
- 新材料不会中途改变已运行轮次的快照。补充要求、失败重试或重启恢复后，在新轮次重新构建；任务中旧快照的 `revision` 可用于识别它属于哪个轮次。

此阶段不安装向量服务，不调用模型构建记忆。SimpleMem、中文混合检索、索引异步刷新、删除联动与长期记忆实验属于后续 M1/M2；当前只是可替换接口及基础路径。

## 四类输出与实际可用范围

输出位于 `task.interaction`。统一包含 `contract_version`、`task_id`、`work_id`、`revision`、`output_version`，与旧 `card_version` 分开计数。任务继续处理后仍可返回上一条输出，客户端必须对照输出 revision 和当前任务 revision，不能把旧卡片当新输出。

| kind | 当前行为 | 回应 |
| --- | --- | --- |
| information | 校验并发布即 completed，不写正式状态 | acknowledge 可选，仅记录已阅 |
| clarification | 模型读取证据后可调用 ask_clarification；任务等待回答 | answer，绑定 question_id；同一 task_id 的 revision 加1后继续 |
| state_proposal | 保存候选与 base_state_version，等待确认 | accept_state / reject_state，绑定 proposal_id；接受后事务提交，拒绝取消该任务 |
| action_proposal | 仅提供协议 Schema，execution_enabled 固定 false | 本阶段不发布、不授权、不执行外部动作 |

向 `POST /api/tasks/{tid}/responses` 提交回应，公共字段为 `contract_version: "cloud-v1"`、`event_id`、`expected_revision`、`output_version` 和 `kind`。`answer` 另传 question_id 和 answer；状态回应另传 proposal_id。不得由客户端传入任意 candidate_state。

同一 event_id 和同一内容重试返回 `replayed: true`，不重复恢复或提交；同键不同内容返回409。过期轮次/输出、取消后回答和状态版本冲突返回409；回应类型/目标错误返回422。成功回应后，重试返回当前任务状态，不保证返回历史回应时刻的快照。

cloud-v1 状态确认不能绕过 responses 走旧 confirm。澄清回答也必须绑定 question_id。`actions` 仍允许取消、失败重试、运行中补充材料，以及状态提议阶段的修订；状态提议修订后必须等待新输出并重新确认。纯信息结果已完成，进一步要求需新建任务。

示例见 [phase-one-contracts.json](../examples/phase-one-contracts.json)。示例为合成值，真实 task_id、question_id、proposal_id 必须使用服务端返回值。Mock 不做自然语言澄清推断；自动提问能力由确定性脚本 Provider 测试验证，真实模型行为仍需专门评测。

## 端侧联调步骤

1. 把 OCR 结果映射为现有 ScreenInput，上传 `/api/screens`；bbox 归一化到0—1000。当前没有 OCR 引擎或新识别准确率声明。
2. 调用事项解析接口；用户选择已有事项，或创建事项，或执行一次性整理。
3. 创建 cloud-v1 任务并轮询详情，根据 `interaction.kind` 呈现卡片或问题。
4. 发送对应回应，保留 event_id 以支持网络重试。遇409刷新任务与事项，不自动接受重算结果。
5. 读取正式事项版本确认提交结果，区分显示输出、用户接受与实际行动。

在专用 mock 服务上运行 `python scripts/phase-one-smoke.py --base-url http://127.0.0.1:8765`，验证两轮上传、关联、生成、确认、重复回应和同事项历史读取。脚本会创建合成事项并保存报告到 `artifacts/phase-one-http-report.json`，检测到真实 Provider 会拒绝运行。
