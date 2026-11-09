# v0.4：增强记忆、Schedule、辅助委派与授权动作

实施日期：2026-11-09。继续在 `Cloud Agent` 内开发，沿用通用框架优先的定位。Party 与 Schedule 用来验证同一套事项、上下文、执行和交互机制。M0 说明保留为历史阶段记录，本文件描述 v0.4。

## 实现与边界

| 模块 | 本版实现 | 边界 |
| --- | --- | --- |
| SimpleMem | 直接调用固定提交的上游 MemoryBuilder 文本提示构建与解析方法；每次只处理一个有确定来源的材料块 | 没有复现论文完整流水线、跨窗口合并、多模态或作者评测 |
| 检索 | LanceDB 向量、中文字/二元词与英文词、实体关键词，采用 RRF 融合；回查 SQLite 原文 | hash-demo 是词法哈希向量，不是语义模型；Qwen 必须安装权重后显式选择 |
| Schedule | 按稳定条目 ID 提交变更，明确时间、先后依赖、冲突澄清、用户完成反馈 | 最多20项；不自动推断时间已过等于已完成；mock 只识别合成 JSON |
| 辅助委派 | 主能力按白名单调用只读 TimeCheckAgent，保存父子关联与结果 | 目前仅确定性区间重叠检查，无递归委派、自主团队、全日历查询 |
| 外部动作 | 动作预览、独立授权、持久化执行记录、结果回执、未知结果核对 | 导出文件不等于用户已导入日历；CalDAV 真实账号尚未实测 |

## 记忆数据流

SQLite 中的正式状态、原始证据与派生记忆分开保存。任务只在用户接受状态提议后提交正式状态。SimpleMem 摘要、实体和向量只帮助找材料，不获得修改正式状态的权限。

材料关联到事项或收到用户修订后，框架写入 `memory_jobs`。后台在数据库事务之外提取、编码与写入 LanceDB，成功后保存带来源和代次的 `memory_entries` 白名单。删除或重建会改变代次；迟到结果无法恢复已删除材料。

上下文组装始终保留当前正式状态、本轮屏幕、认证用户输入。增强检索融合向量、词法和实体结果，再取回授权范围内的原始材料。它补入尚未索引或构建失败的相关原文，并保留基础近期历史作为后备；历史预算为12,000字符，当前筛选最多2,000条已索引记忆、100条未完成构建记录。规模扩大前需要分页或服务端过滤优化。

每项构建最多尝试3次；失败不会使前台任务失败，重建可重置尝试次数。单次模型调用有超时和4,096输出 token 上限，截断输出被拒绝。检索最多等待30秒，超时继续基础上下文。启动缺失依赖或权重时降级到基础记忆，`/api/health` 的 `memory_requested`、`memory_mode`、`memory_error` 能区分请求配置与实际模式。

删除屏幕会清除其原文、可检索白名单和相关缓存，并使尚未结束的关联任务失效；索引删除由后台完成。**这不是整个事项的数据擦除**：已经确认的正式状态、历史卡片和版本记录仍会保留。索引未启用时删除任务会等待以后启动或重建处理；白名单和原文检查已经阻止再次召回。LanceDB 逻辑删除也不等于存储介质的安全擦除。

来源映射由框架负责，不信任模型返回的来源 ID。消息时间缺失时保持未知，截图采集时间不作为消息发生时间。使用上游私有提示/解析接口是有意的适配选择，故锁定提交并设有隔离测试；上游升级必须复测。

## 安装与配置

在 `Cloud Agent` 目录执行。默认基础模式只需 `.[test]`，其余均为可选依赖。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test,memory]"
# 固定上游提交，只安装文本适配需要的依赖，避免拉入全部多模态包。
.\.venv\Scripts\python.exe -m pip install --no-deps "simplemem @ git+https://github.com/aiming-lab/SimpleMem.git@db80b6a7c591e0ea730a058e9f5fc4eb06572299"
# 使用真实 Qwen 编码器时：
.\.venv\Scripts\python.exe -m pip install -e ".[qwen]"
.\.venv\Scripts\python.exe scripts/prepare-qwen.py
```

SimpleMem 上游引用：[固定提交](https://github.com/aiming-lab/SimpleMem/tree/db80b6a7c591e0ea730a058e9f5fc4eb06572299)。LanceDB 版本0.25.3；编码依赖 sentence-transformers 5.1.1、transformers 4.57.1。Qwen 下载脚本默认固定 [97b0c61 提交](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B/tree/97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3)，约1.2GB权重；服务启动不会自动下载。脚本下载后进行真实编码检查，输出 `artifacts/qwen-check.json` 才代表该检查成功。网络中断可重新执行；可选安装官方 `hf-xet` 下载支持。

注意：项目内已创建独立 `.venv`。如果使用 uv 创建的环境而没有 pip，可用 `uv pip install --python .venv/Scripts/python.exe ...` 替代上面的安装命令。

离线工程验证：

```ini
SCREENMIND_MEMORY_MODE=hybrid
SCREENMIND_MEMORY_EXTRACTOR=raw
SCREENMIND_EMBEDDING=hash-demo
```

增强文本记忆与真实语义编码：

```ini
SCREENMIND_MEMORY_MODE=hybrid
SCREENMIND_MEMORY_EXTRACTOR=simplemem
SCREENMIND_EMBEDDING=qwen
SCREENMIND_EMBEDDING_MODEL_PATH=artifacts/models/Qwen3-Embedding-0.6B
SCREENMIND_MODEL_BASE_URL=https://maas.whu.edu.cn/v1
SCREENMIND_MODEL=deepseek-flash
SCREENMIND_MODEL_API_KEY=<本地配置密钥>
```

任务模型是否真实调用由 `SCREENMIND_PROVIDER=openai-compatible` 单独控制。即使任务模型是 mock，选择 simplemem 也会调用配置的真实模型构建记忆。确认材料已获上传许可后再启用。构建消耗模型调用，不能只统计最终任务请求成本。默认不启用增强模式。

切换抽取器、编码器或模型权重后，对相关事项执行索引重建。服务只用当前抽取器和编码器的白名单，不混用旧配置产生的记忆；旧模型索引不会自动迁移或清理。

## Schedule 与交互

创建事项时指定 `subagent_id: schedule`，创建任务选择 `interaction_protocol: cloud-v1`。网页的“体验日程场景”已经自动设置这些值。输入块可带 `message_at`（带时区）。

mock 合成屏幕示例：

```json
{"schedule_changes":[{"id":"material","title":"提交材料","at":"2026-12-10T17:00:00+08:00","before":["defense"]},{"id":"defense","title":"答辩","at":"2026-12-10T18:00:00+08:00"}]}
```

把答辩改到16点会与材料17点的明确依赖冲突；框架发布 clarification，用户通过 `answer` 回应。mock 回答可填写 `{"schedule_changes":[{"id":"material","at":"2026-12-10T15:00:00+08:00"}]}`。真实模型使用自然语言。候选通过 `accept_state` 才写入正式状态，重复或较早通知不增加正式版本。没有可靠消息时间的修改需要用户澄清确认。

完成反馈同样先产生候选，再确认；屏幕里出现“已完成”不能直接当作认证用户的完成反馈。

## 辅助 Agent 契约

主能力通过 `auxiliary_tools=("time_check",)` 注册。Worker 先读上下文，才开放 `delegate_time_check` 的调用；工具参数包含一个候选区间和最多30个已有区间，每项必须引用该轮上下文白名单来源。

辅助能力只接收这些区间，不得到数据库、主任务工具或状态写权限。默认5秒超时；委派前后检查父任务 revision 和正式状态版本。父任务取消会传播取消；状态过期的结果丢弃。同轮相同参数成功结果可幂等复用。`GET /api/tasks/{id}/delegations` 提供审计记录。

返回 `scope: provided_intervals_only`，表明“传入区间内未发现冲突”，不表示完整日历无冲突。主能力仍负责解释结果和生成候选，框架负责确认提交。

## 动作执行协议

1. 正式日程已确认后，调用 `POST /api/works/{work_id}/action-proposals`。提供 `event_id`、`expected_state_version`、`item_id`、带时区的 `end`，以及 `tool: calendar.export` 或 `calendar.caldav`。服务从已确认状态读取标题和开始时间。
2. 返回独立的 `action_proposal` 任务，包含动作目标、完整参数和正式版本。预览阶段没有外部副作用。
3. `POST /api/tasks/{task_id}/responses` 提交 `authorize_action` / `reject_action`、`event_id`、`expected_revision`、`output_version`、`action_id`。客户端不能借回应修改参数。
4. 后台从 authorized 状态领取动作，调用前再次检查正式版本；已变化则 stale。成功需要执行器提供回执。授权本身不等于执行成功。
5. `GET /api/actions/{action_id}` 查询结果。导出成功后使用 `GET /api/actions/{action_id}/artifact` 下载 ICS。
6. 超时、中断或无法确认的远端结果记为 unknown，不自动重试；`POST /api/actions/{action_id}/reconcile`（JSON `{}`）只核对文件或远端 GET，不再次执行 PUT。已授权但尚未执行的动作在重启后可继续；执行中动作重启后变为 unknown。

相同事项/条目/工具有进行中或结果未知的动作时，拒绝另建或重复授权；当前正式版本已成功时也拒绝重复创建，应查看原回执。动作记录与普通分析任务重试隔离。

`calendar.export` 实际写入本地 `.ics`，回执明确 `calendar_imported: false`。网页支持预览、授权、下载与未知结果核对。

`calendar.caldav` 仅在配置固定 HTTPS 集合地址和凭据后注册；实际执行条件 PUT `If-None-Match: *`，采用动作固定 UID。412 会 GET 核对已有记录。预览绑定目标集合，配置改变后不能将旧授权发往新地址。需要服务端支持标准 CalDAV 集合与条件写入；只实现创建事件，不负责 OAuth、服务发现、重复日程、更新/删除或双向同步。网页当前只提供导出入口，CalDAV 通过 API 使用。

## 新增运维接口

| 接口 | 用途 |
| --- | --- |
| `GET /api/works/{id}/memory-index` | 检查构建任务状态，确认是否启用增强记忆 |
| `POST /api/works/{id}/memory-index/rebuild` | 请求重建，发送 JSON `{}`；不会修改正式状态 |
| `DELETE /api/screens/{id}` | 删除原始屏幕、移除检索资格、失效相关未结束任务 |
| `GET /api/tasks/{id}/delegations` | 查看辅助委派过程 |
| `GET /api/contracts` | 当前交互协议、动作执行器与实际记忆模式 |

复用现有 API Key、工作区隔离和单进程限制。没有新增多用户登录，尚不应当作多租户服务部署。

## 验证与下一步

验证记录见 [phase-two-validation.md](phase-two-validation.md)。重跑入口：

```powershell
.\.venv\Scripts\python.exe -m pytest -q --basetemp=artifacts/pytest-v04 --junitxml=artifacts/v04-tests.xml
.\.venv\Scripts\python.exe scripts/check-simplemem.py --run
.\.venv\Scripts\python.exe scripts/check-memory.py --run
.\.venv\Scripts\python.exe scripts/check-schedule.py --run
```

真实调用脚本读取本地 `.env`，也可用 `--env-file` 指定已有配置；只使用合成测试材料，不操作真实日历。浏览器脚本 `scripts/phase-two-browser.cjs` 连接隔离的本机服务，验证 Schedule → 状态确认 → 动作预览 → 授权 → 文件下载，并检查390px宽度布局。

下一阶段重点是：完成 Qwen 实际权重环境验收、用同一冻结测试集比较完整历史/基础记忆/混合检索的正确率与总成本；接入真实手机输入和真实 CalDAV 测试账号；验证新增插件的端云复用。没有这些结果前，不宣称增强记忆已提升业务准确率或达到比赛评测指标。
