# 端侧与云端接口契约 v0.2

基础地址示例：`http://127.0.0.1:8765`。请求与响应均为 UTF-8 JSON。完整模型见运行服务的 `/openapi.json`。

## 1. 上传一份屏幕记录

`POST /api/screens`，返回 HTTP 201：

```json
{
  "event_id": "device-a-screen-001-v1",
  "session_id": "registration-session",
  "app_package": "com.tencent.mm",
  "app_name": "微信",
  "page_title": "项目群",
  "captured_at": "2026-11-07T10:02:00+08:00",
  "blocks": [
    {"id": "message-1", "kind": "chat", "speaker": "老师", "text": "周四 17:00 前提交预审材料。"}
  ]
}
```

响应为 `{"screen": {"id": "scr_...", ...}, "replayed": false}`。上传只保存材料，不自动建任务。

- `event_id` 是调用方生成的稳定请求 ID。相同 ID、相同内容重试返回同一条记录；相同 ID、不同内容返回 409。
- 一份屏幕记录不可变；OCR 拼接内容更新时使用新的 event_id。
- `session_id` 是端侧会话标识，仅作为材料元数据。当前版本不根据它自动路由或关联任务。
- `captured_at` 必须带时区；不能使用无时区字符串。
- `blocks[].id` 在一份屏幕内唯一。`kind` 为 `paragraph/chat/card/key_value`。
- `bbox` 可选，使用 `[left,top,right,bottom]`，归一化到 0–1000。
- 每屏最多 60 个块、单块 4000 字符、整屏 24000 字符；原始截图不在协议内。

### 与现有 T 文档对接

T 文档的 `structured_payload` 不会被后端直接当作任意 JSON 接受。端侧接入适配层转换如下：

| T 文档字段 | 文本块接口（v0.1 起兼容） |
| --- | --- |
| `session_id` / `app_package` / `app_name` / `page_title` | 同名字段 |
| `last_updated_timestamp` | 转换为带时区的 `captured_at` |
| `chat_bubbles[]` | 每个气泡一个 `kind=chat` 的 block，保留 speaker |
| `product_cards[]` | 每个商品卡片一个 `kind=card` 的 block，合并标题、价格、标签为文字 |
| `key_values` | 每个键值对一个 `kind=key_value` 的 block |
| `paragraphs[]` | 每段一个 `kind=paragraph` 的 block |
| `telemetry` | 第一版暂不接收，后续单独扩展遥测接口 |

调用方另提供 event_id 和各 block 的稳定 id。首次先统一文本块协议，以免云端依赖每种 App 的版式。

## 2. 发起任务

`POST /api/tasks`，HTTP 202：

```json
{"event_id":"request-001","instruction":"整理报名要求并保留出处","screen_ids":["scr_..."]}
```

响应：`{"task": {...}, "replayed": false}`。`task.id` 是后续操作的定位标识。每个任务最多 20 份屏幕，源文字总计最多 48000 字符。

Router 根据 work_id 绑定的能力分派；没有事项时可显式传 subagent_id，否则匹配能力关键词。已有 task_id 的操作进入原任务。未让 LLM 判断任意 OCR 是否值得自动启动任务。

## 3. 查询与订阅

- `GET /api/screens`：最近 100 份屏幕，包含正文。
- `GET /api/tasks`：最近 100 个任务摘要。
- `GET /api/tasks/{task_id}`：完整任务、输入历史与最新卡片。
- `GET /api/events?after=0&task_id=...`：按序号升序返回最多 200 个事件。持续带最后一条的 seq 读取后续页。
- `GET /api/events/stream?after=0`：SSE，事件名 `update`，`id` 为持久化 seq；支持 `Last-Event-ID`。每 15 秒发送注释心跳。

SSE 示例：

```text
id: 12
event: update
data: {"seq":12,"task_id":"task_...","kind":"card.published","payload":{"revision":1,"version":1,"mode":"mock"},"at":"..."}
```

接到状态变化后重新读取任务详情，不把事件当作完整卡片。配置 API Key 时，SSE 客户端也需要 Bearer Header；浏览器原生 EventSource 不便设置 Header，可用 fetch 流或采用轮询。

## 4. 卡片格式

```json
{
  "type": "information",
  "title": "报名要求",
  "summary": "请检查识别结果。",
  "fields": [{
    "id": "deadline",
    "label": "截止时间",
    "value": "周四 17:00",
    "source_refs": ["scr_...:message-1"],
    "origin": "source"
  }]
}
```

`source_refs` 必须指向任务的真实 block、其事项快照 `memory:<work_id>:<version>` 或用户补充 `input:<task_id>:<revision>`。校验引用存在不代表语义已验证，真实模型仍可能错误理解原文。用户修订由服务端标记为 `origin=user`，来源说明此字段最初的材料，不能将修订值冒充原始 OCR 内容。

每张卡片最多 24 个字段，每个值最多 1000 字符。模拟模式按输入顺序取前 24 个块，只做确定性整理。

## 5. 修改后继续

`POST /api/tasks/{task_id}/actions`，HTTP 202：

```json
{
  "event_id": "edit-001",
  "expected_revision": 1,
  "kind": "revise",
  "card_version": 1,
  "edits": {"deadline": "周四 16:00"},
  "message": "按我的修订继续整理。",
  "screen_ids": []
}
```

`message`、`edits`、`screen_ids` 至少提供一种有效变化。字段编辑仅在 `waiting_input` 时允许；运行中可提交文字要求或补充屏幕，这会取消旧一轮执行，增加 revision，并从任务输入重新计算。原任务 ID 不变。

- `expected_revision` 必须等于当前任务 revision；不一致返回 409。
- 修改字段必须带最新 `card_version`；旧版本返回 409。
- 字段编辑后，新卡片保留用户修订值；可以通过下一次编辑再次修改。
- 新输入提交成功后，之前的卡片只作为历史结果，不能立刻确认旧卡片。

## 6. 确认、取消、重试

同一个 actions 接口：

```json
{"event_id":"confirm-001","expected_revision":2,"kind":"confirm","card_version":2}
```

`confirm` 接受当前卡片并结束任务；存在 memory_proposal 时，同一事务内把候选状态写入所属事项并增加 memory_version。不会写系统日历或执行外部操作。

```json
{"event_id":"cancel-001","expected_revision":1,"kind":"cancel"}
```

取消会增加 revision、终止本地执行协程；已送达模型服务的计算是否停止取决于服务商，但旧结果不能写回任务。

```json
{"event_id":"retry-001","expected_revision":1,"kind":"retry"}
```

仅失败任务可以 retry。completed/cancelled 任务不能继续修改，需要创建新任务。

## 错误与身份边界

`401` 缺少/错误 API Key；`403` 跨站或非本机无 Key 请求；`404` 资源不存在；`409` 幂等内容冲突或过期版本；`413` 请求体过大；`415` 非 JSON；`422` Schema 或上下文预算不满足。

客户端不能自行指定 owner。第一版每个部署只服务一个用户工作区。任务之间只能读取各自明确关联的屏幕；不能让模型通过工具请求任意其他屏幕 ID。


## 7. SubAgent 与事项记忆

- `GET /api/subagents` 返回 id、name、description、requires_work。
- `POST /api/works` 创建独立事项；event_id 支持幂等。
- `GET /api/works` 返回最近 100 个事项及其当前已确认状态。
- `GET /api/works/{work_id}` 返回 work 和最近 100 个历史版本 history（新版本在前）。

创建事项（HTTP 201）：

```json
{"event_id":"party-work-001","title":"周六生日聚会","subagent_id":"party"}
```

响应 `{"work":{"id":"work_...","subagent_id":"party","memory_version":0,"state":{},...},"replayed":false}`。

上传 Party 初始屏幕后创建任务（HTTP 202）：

```json
{"event_id":"party-task-001","work_id":"work_...","instruction":"整理聚会计划并检查预算与分工","screen_ids":["scr_..."]}
```

`subagent_id` 可省略，Router 从事项读取。显式传入其他能力返回 422。Party 必须关联事项；旧版 information 请求可继续省略两个字段，作为独立任务运行。

任务详情新增 `work_id`、`subagent_id`、`memory_base`、`memory_snapshot`、`memory_proposal`。其中 base/snapshot 在开始执行时确定，proposal 仅在有效结果发布后出现。任务摘要不返回完整记忆快照与候选状态。

**确认冲突**：同一事项另一任务先确认后，本任务旧 memory_base 不能覆盖新版本，confirm 返回 409。客户端保留草稿并提示重新生成；使用 revise 补充明确要求或屏幕，读取最新记忆生成新候选，再确认。重复相同 confirm event_id 不会重复增加记忆版本。

**派生字段**：CardField 新增 `editable`，默认 true。Party 字段为 false；客户端应只读显示，服务端也拒绝 edits 修改。通过 revise.message 修订成员状态或责任分工。用户补充有独立 input 引用。

**新变化**：确认初始任务后，上传退出消息，创建相同 work_id 的新任务即可。该任务只需关联新消息屏幕，旧计划通过记忆提供。屏幕首次绑定后不能迁到另一事项，返回 422。重试上传仍使用原 event_id；新的独立事项请使用新的屏幕事件。

新事件：`work.created`、`memory.committed`。`router.dispatched` 包含 subagent_id、work_id、reason。完整 Party 状态、演示文本格式和插件扩展方式见 [SubAgent 文档](subagents.md)。
