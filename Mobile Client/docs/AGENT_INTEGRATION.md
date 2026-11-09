# 微光 v0.2 · Cloud Agent 集成

手机 → `/v1` 认证与线索网关 → `/v1/agent` 适配器 → 共享 Cloud Agent 引擎。原 `/v1/decisions` 的规则演示独立保留，线索协议仍为 schema_version=1。Agent 交互采用 `cloud-v1`，不是将旧反馈接口改名。

## 启动与配置

完整检出 ScreenMind；需要 Python 3.12+。在 `Mobile Client` 执行 `powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1`。脚本安装精确版本的网关依赖和相邻 Cloud Agent 的可编辑包。默认一个进程、端口 8765，不要增加 uvicorn workers。

手机注册/登录后，在首页进入“云端事项”。默认 mock/basic 可以无模型费用验证流程。若要启用真实模型，将 `Cloud Agent/.env.example` 复制为同目录 `.env`，填入以下字段后重启网关：

```dotenv
SCREENMIND_PROVIDER=openai-compatible
SCREENMIND_MODEL_BASE_URL=https://maas.whu.edu.cn/v1
SCREENMIND_MODEL=deepseek-flash
SCREENMIND_MODEL_API_KEY=在本地填写
```

配置文件由 Cloud Agent 定位读取，环境变量优先。不要将密钥写入 Android BuildConfig 或提交仓库。手机继续用注册得到的 Bearer 会话，不接触模型密钥或独立云端 API Key。`SCREENMIND_API_KEY` 不替代手机认证。

基础运行不需要 SimpleMem、LanceDB 或 embedding 模型。增强记忆安装见 `Cloud Agent/docs/phase-two.md`；应安装到**实际运行网关的 Python 环境**，不是仅安装在另一个虚拟环境。Qwen 权重路径建议使用绝对路径。每个账号的索引可重建，正式状态与原始证据保存在独立 SQLite。

`WEIGUANG_DB` 控制手机业务数据库；`WEIGUANG_AGENT_ROOT` 控制账号工作区根目录，默认手机数据库旁的 `agents`。路径不能放进公开静态目录。同库进程锁限制为单进程。当前一个进程最多打开 8 个账号工作区，尚无闲置回收，第 9 个返回 503；这是小规模内测实现，不是生产多租户调度服务。

## 用户流程

1. OCR 或手动记录后，核对文字和来源，明确允许上传并等待“已同步”。
2. 创建 Information / Party / Schedule 事项，或选择已有事项。勾选本次允许使用的线索（最多 10 条），填写要求并提交。
3. 任务异步执行，页面轮询；可以离开后重新从任务列表打开。事项历史包含此前该事项已授权的材料。
4. 缺信息时回答澄清。候选方案可补充要求、拒绝或确认；只有确认才更新正式状态版本。
5. 已确认的 Schedule 条目可预览日历导出，用户提供结束时间。开始/结束时间、目标展示后，另行点击授权。
6. 导出成功后保存 ICS，再在日历应用导入。服务器生成、手机保存、系统日历导入是三个不同事实，界面不把保存称为导入。

mock 是规则化测试模型，不能期待它理解任意自然语言。Schedule 合成线索可使用 `{"schedule_changes":[{"id":"meeting","title":"组会","at":"2026-12-10T18:00:00+08:00"}]}`；真实模型可使用自然语言。

## 公共接口

全部需要手机会话；账号由服务端令牌解析。请求体不接受决定归属的 owner/user_id。完整字段以 `docs/openapi.json` 和运行中的 `/docs` 为准。

| 路径（前缀 `/v1/agent`） | 方法 | 职责 |
|---|---|---|
| `/health`, `/subagents` | GET | 实际模型/记忆状态与可用能力 |
| `/works`, `/works/{id}` | GET | 本人事项列表、正式状态 |
| `/works` | POST | event_id 幂等创建绑定能力的事项 |
| `/tasks` | POST | request_id、work_id、instruction、events[{event_id,revision}] |
| `/tasks`, `/tasks/{id}` | GET | 本人任务列表、交互结果与证据映射 |
| `/tasks/{id}/responses` | POST | cloud-v1 回答、确认/拒绝状态、授权/拒绝动作 |
| `/tasks/{id}/actions` | POST | 修订、取消、重试；带 expected_revision |
| `/works/{id}/action-proposals` | POST | 依据正式版本创建动作预览 |
| `/actions/{id}` | GET | 执行回执 |
| `/actions/{id}/reconcile` | POST | 对 unknown 结果核对，避免直接重做 |
| `/actions/{id}/artifact` | GET | 已成功导出的 ICS 二进制文件 |
| `/actions/{id}/calendar` | GET | Android 保存用的 UTF-8 内容和 calendar_imported=false |

手机本轮仅开放 `calendar.export`。每个账号引擎明确清除全局 CalDAV 凭据，因此不能通过手工请求把用户日程写入同一个服务器管理员日历。每账号日历连接与凭据管理后续单独实现；独立单工作区 Cloud Agent 原有 CalDAV 能力保留。

## 证据、隔离与一致性

- 认证 UID 映射到独立数据库、索引与导出目录。客户端猜到其他账号的 work/task/action ID 也无法访问。组内共享并不自动授权 Agent 读取对方线索。
- 一条事件以 `(event_id, revision, work_id)` 映射为 Cloud screen；因为来源归属事项，同一事件在不同事项中分别登记。单条 10000 字上限保持，分成不超过 4000 字的文本块，未静默截断。
- `occurred_at` 是事项发生时间；`captured_at` 是采集时间。二者都不冒充消息发送时间。原始文字保留，时间与来源类别作为明确标注的元数据块。
- 更新或删除线索时，同一手机数据库事务写入失效队列。随后清理旧来源、授权历史引用并中断受影响任务。服务重启后，访问该账号 Agent 的首个请求先处理队列，包括确认请求。
- 旧候选方案不可继续确认；旧来源也不可重试。已确认正式状态保留，它代表此前用户确认的结果；删除来源不是删除所有派生业务事实或清空整个事项。
- 正式状态、候选输出与外部动作使用 Cloud 现有版本校验。收到新材料不会覆盖已确认安排，模型输出不会直接取得外部动作授权。
- Android Room 从 v1 增量迁移至 v2，保留线索与建议，新增按账号隔离的待确认请求表。先持久化原始请求，再发送；网络结果不明时按同一个幂等 ID 重试，不重新生成动作。确定的 4xx 校验错误清除待重试命令；用户刷新后重新操作。
- 前端只渲染明确支持的 cloud-v1 类型；未知协议显示升级提示，不执行返回内容中的任意动作。

## 代码入口与后续边界

`backend/agent_bridge.py` 负责适配及隔离，`backend/main.py` 负责认证/事件及失效队列。Cloud 的模型、Router、插件、记忆、执行器仍只有一套实现。`Repository.kt` 管理会话与持久化请求，`AppViewModel.kt` 编排手机操作，`ui/AgentScreen.kt` 呈现交互。能力选择列表来自云端注册表；新插件沿用 cloud-v1 卡片类型时可以复用任务交互，新业务特有的正式状态摘要或工具表单仍可另外扩展。

本轮没有实现群组多人共同编辑同一事项、后台自动观察聊天、手机直接操作第三方聊天应用或完整账号注销清除。公开部署前仍需 HTTPS、注册策略、集中限流、备份、多租户资源配额与每账号外部连接。当前目标是可验证的端云内测链路。
