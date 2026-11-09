# ScreenMind Super Agent · 第一阶段框架 v0.2

结构化 OCR → Router → 可注册 SubAgent → 交互卡片 → 用户确认 → 独立事项记忆。首个业务能力是 Party 聚会协调，原有信息整理能力继续可用。

本项目借鉴 DeepSeek Super-agent 的事件、任务、Worker 隔离与状态持久化设计，独立实现移动端所需的精简服务。没有复制或部署 DeepSeek Harness 原型。默认使用模拟模型，**模拟输出只证明框架流程，不代表 AI 理解能力**。

## 立即运行

需要 Python 3.12+。从仓库根目录先执行 `cd "Cloud Agent"`，再在本目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\run.ps1
```

已安装依赖的环境也可以直接执行：

```powershell
python -m uvicorn screenmind.api:create_app --factory --host 127.0.0.1 --port 8765 --no-proxy-headers
```

- 工作台：<http://127.0.0.1:8765>
- OpenAPI 文档：<http://127.0.0.1:8765/docs>
- OpenAPI JSON：<http://127.0.0.1:8765/openapi.json>
- 默认数据库：`data/screenmind.sqlite3`，重启不会清空。
- 使用**单个进程**，不要增加 `--workers`。进程锁防止多个调度器使用同一个数据库。

网页操作：点击“载入示例屏幕” → 勾选材料 → “开始处理” → 修改卡片字段 → “保存修改并继续” → “确认完成”。示例屏幕为合成数据，不代表正式赛事要求。

无界面联调：在服务运行时执行 `python scripts/demo.py`，它会上传示例、建任务、修改字段、继续处理并确认完成。

## 体验 Party 场景

点击 **体验 Party 场景** → **开始处理** → 确认 8 人初始计划 → **模拟：小林临时退出** → **开始处理**。

结果显示确定参加 7 人、固定套餐 ¥688 对应约 ¥98.29/人、蛋糕负责人待重新分配。此时上方记忆仍是 8 人旧计划。补充 `蛋糕负责人：小王` 并继续处理，再确认，事项记忆从 v1 更新到 v2。

另有“可能来不了”示例，用于验证待确认状态。创建多个 Party 会生成各自独立的 work_id 和记忆。默认 mock 使用明确的演示文本格式，不具备通用自然语言理解能力；详见 [SubAgent 扩展与 Party 约束](docs/subagents.md)。

## 已实现的边界

| 能力 | 当前实现 |
| --- | --- |
| OCR 输入 | 接收已结构化、已脱敏的文字与可选坐标，不截屏、不运行 OCR |
| Router | 独立路由类：事项绑定优先，其次显式选择，最后插件关键词匹配；不明确时要求选择 |
| SubAgent | 注册表、统一插件契约、InformationAgent 与 PartyAgent；Worker 动态使用插件 Schema 与规则 |
| 事项记忆 | work_id 隔离、已确认状态、候选状态、版本历史、并发覆盖保护 |
| 执行约束 | 默认 2 个并发任务、每轮最多 6 次模型请求、总超时 120 秒 |
| 持续交互 | 补充要求或新屏幕、字段修订、确认、取消、失败重试 |
| 持久化 | 屏幕、输入、任务状态、卡片版本、事件日志及幂等记录 |
| 恢复 | 中断的 running 任务重启后增加 revision 并重新排队，从该轮开始重算 |
| 模型 | 模拟模式；兼容 Chat Completions 的工具调用适配器 |
| 可观测性 | 真实状态、工具名、工具校验失败和耗时；不保存或展示模型内部推理 |
| 界面 | 桌面/手机宽度的网页调试台；1.2 秒轮询，后端另提供 SSE |

**尚未实现**：语义 Router、自动主题关联、跨事项长期语义检索、多 Agent 团队、MCP、主动任务、Android 原生客户端、多用户登录、智能 Watchdog、外部写操作。

当前不是部署到公共网络的多租户服务。一个部署仅对应一个工作区，API Key 代表该工作区。内部查询包含 owner 边界，但不提供多用户账号体系。

## 接入真实模型

需要支持 `tools` / `tool_calls` 的 Chat Completions 模型。以下是 PowerShell 配置方式；不要把实际密钥提交到仓库。

```powershell
$env:SCREENMIND_PROVIDER = 'openai-compatible'
$env:SCREENMIND_MODEL_BASE_URL = 'https://api.deepseek.com/v1'
$env:SCREENMIND_MODEL = '<服务商当前支持工具调用的模型名>'
$env:SCREENMIND_MODEL_API_KEY = '<你的模型密钥>'
.\run.ps1
```

模型名称由你配置；没有写死未经验证的模型名称。`MODEL_BASE_URL` 应是 API 根地址，服务端会追加 `/chat/completions`。不同厂商的 Schema 支持需要实际联调。模型响应里的 `reasoning_content` 仅在该轮内存消息中按协议回传，不写入数据库、不推送到网页。

真实模式会将任务关联的屏幕文字、用户要求、上一版卡片和当前事项的记忆快照发送给配置的模型服务。它依赖端侧完成脱敏，本服务不承诺自动检测所有敏感信息。

配置缺失会启动失败，真实模型调用失败会将任务标为 failed；**不会静默回退为模拟模型**。已在 `https://maas.whu.edu.cn/v1`、`deepseek-flash` 上完成一次真实 Party 场景验证：初始计划、模糊退出、明确退出、自然语言重新分工及记忆确认全部通过。该结果仅覆盖合成测试样例，不代表普遍语义准确率。

复制 `.env.example` 为 `Cloud Agent` 目录下的 `.env`，服务启动时会自动读取；进程环境变量优先。`.env` 已被 Git 忽略，不要提交或分享。配置采用字面量 `KEY=VALUE`，支持独立注释行和成对引号，不执行变量插值或命令。其他设置：

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| `SCREENMIND_PROVIDER` | `mock` 或 `openai-compatible` |
| `SCREENMIND_DB_PATH` | `data/screenmind.sqlite3` |
| `SCREENMIND_CONCURRENCY` | `2`，范围 1–8 |
| `SCREENMIND_MAX_STEPS` | `6`，范围 2–20 |
| `SCREENMIND_TASK_TIMEOUT` | `120` 秒 |
| `SCREENMIND_MODEL_TIMEOUT` | 单次模型 HTTP 请求超时，默认 `40` 秒 |
| `SCREENMIND_API_KEY` | 空；空时仅接受本机 API 请求 |
| `SCREENMIND_ALLOWED_HOSTS` | `localhost,127.0.0.1,[::1]` |

手机真机连接时，需要显式配置 API Key 和服务器 Host，并调整监听地址。移动端在请求头携带 `Authorization: Bearer <API Key>`；Web 调试台通过“连接设置”输入 Key，仅保存在当前页面内存中。跨站浏览器请求不开放 CORS。远程部署还需要 TLS 和正式身份认证设计。

## 代码导航

```text
screenmind/
  agents/         SubAgent 契约、注册表、信息整理与 Party 插件
  router.py       按显式选择、事项绑定或能力关键词分发
  models.py       输入与卡片 Schema
  store.py        SQLite 事务、状态机、幂等、版本与来源边界
  worker.py       Worker 工具循环、调度器、取消与输出校验
  providers.py    模拟模型 / 兼容 API 的模型适配器
  api.py          HTTP、SSE、本机访问约束与进程生命周期
  config.py       运行配置
  static/         无构建步骤的调试台
examples/         通知、群聊及 Party 初始计划/退出消息示例
docs/             接口契约、设计说明、验证记录
tests/            状态、并发、恢复、接口和模型协议测试
scripts/          API 演示与浏览器验收
```

## 测试

```powershell
New-Item -ItemType Directory -Force artifacts | Out-Null
python -m pytest -q --basetemp=artifacts/pytest-local
```

`--basetemp` 指向专用测试目录，pytest 会管理这个目录，不要指定存放项目资料的目录。环境禁用本机 socket 时，异步测试需在允许本机通信的执行环境运行。

浏览器测试需额外安装 Playwright：`npm install --no-save playwright`，并安装浏览器，或用 `SCREENMIND_BROWSER` 指向本机 Chrome/Edge 可执行文件。启动服务后执行 `node scripts/browser-smoke.cjs`。该脚本预期使用模拟模式，建议指定专用测试数据库。

## 继续开发的顺序

1. 按 [云端实施路线](docs/cloud-roadmap.md) 明确公共契约和基础 MemoryProvider。
2. 根据 [当前接口契约](docs/api-contract.md) 接入 Android，优先打通 Party 闭环。
3. 以 Schedule 验证插件复用，再分阶段适配增强记忆与受控委派。
4. 拟议交互示例位于 `docs/drafts/interaction-contract-v1.2.json`，尚不是当前可调用 API。

设计依据：[DeepSeek 原型领域模型](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/001-domain.md)、[执行机制](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/003-execution.md)、[扩展接口](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/src/core/ports.ts)。


## 当前真实模型接入

仓库默认使用 mock，不包含本机 `.env`、密钥或演示数据库。需要接入武汉大学 MaaS 时，复制 `.env.example` 为 `.env`，设置 `SCREENMIND_PROVIDER=openai-compatible`、`SCREENMIND_MODEL_BASE_URL=https://maas.whu.edu.cn/v1`、`SCREENMIND_MODEL=deepseek-flash`，并在本地填写自己的 `SCREENMIND_MODEL_API_KEY`。使用 `run.ps1` 启动后会读取这些配置。

可复现的合成场景验证（会调用模型并产生相应用量）：

```powershell
.\.venv\Scripts\python.exe scripts/live-model-smoke.py --run
```

此脚本使用单独的 `data/live-model-check.sqlite3`，通过真实 Provider 验证初始 8 人、模糊退出不直接删除、确定退出后的人均费用与责任缺口、自然语言重新分工、取消不提交和确认更新记忆。报告写入 `artifacts/real-model-report.json`，不保存密钥或模型内部推理。运行后端单元测试不会调用真实模型。
