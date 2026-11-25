# Android 首页与卡片接口清单

核对日期为 2026-11-25，前端基线为 ScreenMind 提交 `9766517`。范围覆盖 Android 首页、卡片详情、手机与桌面推演的双向同步，以及线索和云端事项如何进入首页。这里的“已接入”指该提交的代码和本地网关，联调使用合成数据和模拟模型。[前端基线](https://github.com/hickercf/ScreenMind/commit/9766517dce771cfd8a6dbb03397c30d983e54cae) · [视觉参考](https://chatgpt.com/s/m_6b041cdc1568819184370bd2232a90a5) · [云端 Agent 契约](api-contract.md)

## 本次已交付的兼容字段

首页卡片、焦点、归档、卡片详情与 Android 兼容卡片使用同一组 `timing` 字段。查询日期和默认展示时间统一按 UTC+08:00，开始和结束字段保留源时间的偏移。线索网关可额外收 `ended_at` 和 `all_day`。Android 旧版日历导入保留了固定格式的时间范围，网关会从该范围恢复结束时间。只有实际缺失结束依据时才返回空值。

```json
{
  "timing": {
    "startAt": "2026-11-25T12:15:00+08:00",
    "endAt": "2026-11-25T12:45:00+08:00",
    "durationMinutes": 30,
    "allDay": false,
    "completedAt": null
  }
}
```

- 焦点优先显示已确认且进行中的事项，倒计时指向结束时间，否则显示下一项已知开始时间的安排。全天与时间待定事项仍在日程里。
- 采纳空档可提供数值 `duration_minutes`。旧版“停留 30 分钟”格式兼容。顺延同时移动开始和结束，跨过午夜后归入新的展示日期。
- 首页和详情共用来源引用 `source`。能唯一匹配同日采集班次的出票回执更新原卡，并可通过 `receiptEventId` 回溯。返程锚点有目标日期，按该日期能查到对应卡。
- 第一批收发使用现有网关接口。真实天气、附近路线、报价及交易履约仍需接入对应数据源。

## 1. 首页打开时需要什么

首页通过一次快照取齐当日内容。点卡才请求详情。后端决定哪张卡是当前焦点、事项属于哪天、是否存在冲突和可执行操作。前端负责排版、粉色主题、动画、倒计时显示和中文状态文案。

| 界面 | 后端提供的信息 | 当前接入与待补项 |
| --- | --- | --- |
| 日期、摘要、天气 | 查询日期、本地日期文案、当日模式、事项数和待确认数。天气有数据时提供城市、状况、温度和更新时间 | 已有 `date`、`dayMode`、`ambientHeader`。天气在真实账号返回空，摘要可从完整卡片推导。首页当前默认查当日，切换日期可复用现有 `date` 参数 |
| 此刻焦点 | 事件 ID、标题、类型、状态、开始时间、可用的结束时间、倒计时目标、主要操作。没有焦点时返回空 | 已有 `nowFocusCard`、`countdownTargetIso`、`quickCta`、`progressRatio`。已支持进行中焦点和结束倒计时，结束时间依赖线索来源 |
| 日程时间轴 | 按时间排序的卡片，含稳定 `eventId`、类别、生命周期状态、标题、摘要、时间、状态提示、详情入口、是否可移除。可选来源提示、提醒、清单进度 | 已有 `timelineEvents`、`rightBadge`、`prepReminder`、`detailRoute` 等。当前 Android 主体还从 `nativeState` 取数据，见第 4 节 |
| 连接线与异常 | 相邻事件的关联、通勤冲突及需补多少时间、空档分钟数及可选去处、预警原因和受影响事项、可执行备选 | 已有 `connectorToNext` 和正常、冲突、空档、预警四类。真实路况、实时变更和可执行备选待接入 |
| 已完成与跨日 | 已完成数量、可展开的活动摘要。跨日提供分组标识、目标日期、标题与目标事件 ID，去程与返程可分别查看 | 已有 `archivedSection` 和 `multiDayAnchors`。当前已完成详情支持只读摘要，日终统计可后补 |
| 无日程与休息 | 当日模式、可选的附近路线、路线 ID、预计时长和距离、停靠点。无候选时返回空 | 已有 `EMPTY_EXPLORE`、`REST_MODE`、`exploreCard` 和换一批接口。真实账号当前无路线供应 |
| 采集状态与小助手 | 同步或任务有进展时，可提供状态、更新时间和关联事件 ID | 快照有 `capturePill`，现为默认值。采集权限、正在录屏等由 Android 本地状态提供。小助手形象及动画由前端处理 |

首轮联调应保证日期、卡片状态、排序、详情和写回一致。天气、图片、地图和候选路线缺失时，界面可以隐藏对应模块。颜色、圆角、行距和插画由前端维护，现有 `themeToken` 可继续作为兼容提示。[首页快照及详情投影](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/backend/auraflow_contract.py#L67)

## 2. 点开卡片时需要什么

详情用同一个 `eventId` 返回标题、摘要、类别、状态、选中方案、可显示的数据块和底部操作。候选和确认结果用稳定 `planId` 关联。缺少某个模块时返回基础详情，并根据实际能力展示操作。

| 卡片 | 首轮必需数据 | 有真实供应后再显示 |
| --- | --- | --- |
| 日程与会议 | 时间、地点或会议方式、来源、准备事项 `itemId` 与勾选状态、行前提醒、完成状态 | 会议链接和日终统计 |
| 出行与返程 | 出发地、目的地、出发与到达时间、交通方式、班次、方案 ID、当前选择和确认状态。识别到出票回执时关联原卡 | 实时报价与币种、报价更新时间、座位、登机口、供应商确认的可用凭证 |
| 聚餐 | 时间、人数、候选餐厅 ID、店名、地址、当前选择和预约状态 | 参考人均、到达时间估算、供应商确认的座位或取号凭证。与地点相关的数据依赖授权 |
| 购物 | 条目 ID、名称、是否完成、已完成数和总数。可选预算、地点和门店 | 真实价格及更新时间、历史价格、适用优惠与可核销凭证 |
| 漫游与空档 | 路线或去处 ID、地点、开始时间、预计分钟数、建议理由、停靠点和可采纳操作 | 地图坐标、步行路线、距离、营业状态和可用导航 |
| 异常与替代方案 | 预警原因、严重程度、受影响事件、可选方案 ID、变更后的时间或地点 | 外部实时航班、天气或交通事件及其更新时间 |
| 归档与云端事项 | 归档可读摘要及完成时间。云端生成卡提供关联事项 ID、必要的任务 ID 和来源 | 需要在云端处理的事项可跳转到关联页面继续确认 |

当前详情接口已经返回 `header`、`stateBindings`、`detailBlocks` 和 `stickyDock`。方案、地图、趋势图、清单和凭证有对应数据块。部分候选、地图坐标、天气、价格和二维码来自联调示例。正式数据应区分“已确认本次安排”和供应商回执确认的“已出票”“已订座”。「午间书店」已用同一个数值时长派生摘要、详情和结束时刻。[详情数据块](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/backend/auraflow_contract.py#L184)

## 3. 接口与使用时机

以下均相对**手机网关**，首页与线索数据复用账户的 Bearer 会话。现有路径以本地 OpenAPI 与路由实现为准。

| 用途 | 接口 | 请求与接入状态 |
| --- | --- | --- |
| 打开或刷新首页 | `GET /api/v1/home/timeline` | 可传 `date=YYYY-MM-DD`。当前 Android 带 `clientCapabilities=ANDROID_COMPAT`，须保留兼容返回 |
| 打开事件详情 | `GET /api/v1/events/{eventId}/detail` | 默认返回当前顺序的详情块。客户端已调用，部分模块仍从原生兼容数据渲染 |
| 首页实时同步 | `GET /api/v1/home/stream` | 服务端事件流。连接及重连先下发当前快照，后续发送变化。Android 把事件作为刷新信号，重新读首页 |
| 调预算或换方案 | `POST /api/v1/events/{eventId}/recalculate` | 传 `entityKey` 和 `newValue`，支持 `budget`、`selectedPlanId`、`preference`。当前预算用于筛现有候选，真实重报价待补 |
| 确认事项或候选 | `POST /api/v1/events/{eventId}/confirm` | 用详情返回的 `actionId`，按需传 `selectedPlanId`，必带 `idempotencyKey`。返回更新后的详情和 `homeCardPatch` |
| 勾选或取消清单项 | `PATCH /api/v1/events/{eventId}/checklist/{itemId}` | 传 `{"checked": true}` 或 `false`，表达目标状态，返回详情及首页卡片补丁 |
| 收起提醒 | `POST /api/v1/home/reminders/{reminderId}/ack` | 用首页发来的提醒 ID，已验证操作后的状态可被电脑和手机共同读取 |
| 移除误判提案 | `POST /api/v1/home/events/{eventId}/dismiss` | 可传 `reason` 和 `sourceChatId`，只对待确认项生效，保留来源线索 |
| 标记完成与归档 | `POST /v1/home/sdui/actions?live=true` | 现有通用接口传 `{"action":"complete_card","card_id":"…"}`。云端管理的事项回到关联事项处理 |
| 切换日程、漫游或休息 | `POST /api/v1/home/day-mode` | 传 `date` 和 `mode`。接口已接入，按日保存 |
| 换路线 | `POST /api/v1/home/explore/refresh` | 传 `date`，可选位置和排除的 `excludePlanIds`。接口已有，当前真实账号无供应 |
| 顺延、采纳空档或漫游、执行备选 | `POST /api/v1/home/actions/execute` | 传 `actionId` 与对应 `payload`。顺延用目标事件 ID 和分钟数。空档、漫游采纳与备选动作当前有演示范围限制 |
| 上传和管理线索 | `POST /v1/events`、`GET /v1/events`、`DELETE /v1/events/{event_id}` | 复用线索 UUID、版本、来源、采集时间、可选发生时间、时区和确认后的正文。截图 OCR 可附结构化文本 |
| 线索旧版建议卡 | `GET /v1/decisions`、`POST /v1/decisions/generate`、`PUT /v1/decisions/{decision_id}/feedback` | 原有摘要和追问仍走这组接口，使用证据线索及其版本。日程时间轴使用首页接口 |
| 创建或确认云端事项 | `POST /v1/agent/works`、`POST /v1/agent/tasks`、`GET /v1/agent/tasks/{tid}`、`POST /v1/agent/tasks/{tid}/responses` | 继续使用 `work_id`、线索 ID 与版本、任务状态、候选 ID 和版本。需查看关联事项时用 `GET /v1/agent/works/{wid}` 或 `GET /v1/agent/works/{wid}/plan` |

接口版本说明。独立 Cloud Agent 的 `/api/screens` 和 `/api/tasks` 继续作为云端契约的一部分。Android 和桌面推演联调以上表的手机网关路径为准。当前 Android 在时间轴接口返回 404 时回退到 `GET /v1/home/sdui?live=true&follow=true`，部分首页操作也继续使用 `POST /v1/home/sdui/actions`。桌面工具自身的 `/api/catalog`、`/api/run`、`/api/connect`、`/api/current` 属于本地推演界面，由工具将场景步骤请求发给手机网关。[网关首页路由](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/backend/auraflow_routes.py#L135) · [桌面推演服务](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/scripts/home_simulator/server.py#L227)

## 4. 优先补齐的数据和行为

1. **让首页、详情和外部回执更新同一件事。** 线索用现有 `event_id` 和 `revision`，首页与详情共享稳定的 `eventId`。首页卡要能回溯来源或云端事项。保留候选、确认、完成和已移除的状态，外部回执关联原卡。普通定位和无行动意图的笔记保留为线索。
2. **统一时间。** 当前网关按 UTC+08:00 投影日期，保留源时间偏移。首页和详情已经统一开始、结束、时长和倒计时目标，区分全天、时间待定和进行中。时长和状态中文文案由结构化数据生成，避免同一张卡显示两种说法。
3. **先保持现有 Android 可用，再迁移详情渲染。** Android 目前用顶层时间轴确定顺序和焦点，从 `nativeState` 取卡片、提醒、预设和部分详情。详情接口用于标题、摘要及确认可用性等，许多详情内容仍由兼容卡片渲染。前端逐块迁移并验收后再讨论移除兼容字段。机器枚举仅用于程序判断，界面用「待确认」「已完成」等可读文案。[Android 快照解析](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/app/src/main/java/cn/weiguang/app/home/AuraFlowModels.kt#L521) · [详情渲染](https://github.com/hickercf/ScreenMind/blob/9766517dce771cfd8a6dbb03397c30d983e54cae/Mobile%20Client/app/src/main/java/cn/weiguang/app/ui/home/AuraFlowHomeScreen.kt#L424)
4. **把演示能力接到真实来源。** 首轮可用真实线索和已确认云端日程验收通路。有天气、地图或交易供应后再提供城市天气、通勤预计时间、附近路线、报价和凭证。服务端应表达来源、更新时间、可用状态、失败原因。若支持交易，要有明确的用户确认、待处理／成功／失败状态以及可核对的外部回执。
5. **写回和重连按同一账号验收。** 确认重试使用原幂等键，清单重复设置保持同一状态。过期候选或变更后的事项，客户端按接口返回提示刷新。断线重连重新读取完整快照。首轮验收覆盖新增卡、点开详情、确认方案、勾选、收起提醒、移除提案、完成归档、跨日查询，以及同一账号在两端看到相同状态。

已验证的最短链路是控制台发「午间书店」，手机首页自动出现卡片并可打开详情，手机收起提醒后电脑端能查到新状态。完整本地场景共 21 个、79 步，适合作为后续联调回归基线。[开发日志](development-log.md)
