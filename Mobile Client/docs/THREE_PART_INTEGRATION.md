# 三部分集成协议与模拟器验收

日期：2026-11-13。统一 APP 版本：0.4.0 / versionCode 4。

## 模块归属

| 模块 | 职责 |
|---|---|
| ChronoCapsule/core | 复用原工程的 PP-OCRv6 tiny、结构化、端云屏幕契约和跨屏去重，不含独立客户端的网络与旧版卡片界面 |
| Mobile Client/app | 唯一用户入口，统一登录、上传授权、事项选择、Room 队列、同步、澄清、确认和动作授权 |
| Mobile Client/backend | 认证、用户隔离、结构化事件校验、事项归属及 Cloud Agent 桥接 |
| Cloud Agent | 复用现有 Router、SubAgent、任务执行、正式记忆及版本提交逻辑 |

`Mobile Client/settings.gradle.kts` 引用相邻的 `ChronoCapsule/core`。必须检出完整仓库。独立 ChronoCapsule APP 保留为端侧调试工程，其 `/api/*` 客户端不用于正式用户入口。

共享模块直接引用 ChronoCapsule 原有 Kotlin 源文件；模型只打包 v6 tiny 与 v6 字典，不复制或重新训练模型。集成使用 SDK35 与现有 Kotlin/AGP 版本，不要求把主 APP 升级到独立调试工程的 SDK37。手动导入支持 API29+；自动截图服务仅在 API30+ 启用。

## 统一调用链

```text
用户选图 / 明确开启事项自动记录
  → ChronoCapture → 共享 PP-OCR → 消息结构化
  → Room 保存 LocalEvent 与结构化 JSON
  → 登录令牌 POST /v1/events
  → 若已关联事项，以固定 request_id POST /v1/agent/tasks
  → 网关按账号与事项导入 Cloud Agent ScreenInput
  → cloud-v1 候选 / 澄清 → 用户确认 → 正式状态版本更新
```

普通手动导入仍先核对和授权，上传后由用户选择事项并提交。自动记录必须在事项页面明确开启，再在系统中授予截图所需的无障碍权限；目标事项按账号与服务地址存储。退出账号清除该账号的自动记录开关。它将采集到的聊天文字送到用户明确选择的事项，尚不自动判断不同会话与事项的语义归属。

## 结构化事件

保持原有 `schema_version=1`，为 `payload` 新增可选 `screen`。旧客户端只发 `text` 的行为不变。

```json
{
  "event_id": "客户端生成并持久化的 UUID",
  "revision": 1,
  "event_type": "message",
  "source_kind": "screenshot_ocr",
  "captured_at": "2026-11-13T12:00:00+08:00",
  "payload": {
    "text": "周五在A302讨论",
    "screen": {
      "event_id": "evt-capture-id",
      "session_id": "sess-capture-id",
      "app_package": "com.tencent.mm",
      "app_name": "微信",
      "page_title": "小林",
      "captured_at": "2026-11-13T12:00:00+08:00",
      "blocks": [{
        "id": "bubble-1", "kind": "chat", "speaker": "小林",
        "text": "周五在A302讨论", "message_at": "2026-11-13T11:58:00+08:00",
        "bbox": [10, 20, 800, 70]
      }]
    }
  }
}
```

约束：`screen` 沿用 Cloud Agent 的严格 Schema，拒绝额外归属字段、无时区时间及不合法坐标。只允许截图来源携带；事件采集时间与屏幕采集时间一致。`text` 必须等于每个 block.text 以换行连接的结果；直接编辑整段文字后，客户端会清除结构化副本，改为普通线索，避免把旧坐标或发言人误绑到改后的文字。

网关保留 app、page、speaker、message_at、bbox 等数据，基于当前登录用户与明确选定的 work_id 生成内部来源标识。客户端不能提供 owner 或把材料写进其他用户的事项。原始截图不进入上传协议。

用户另外填写的事项发生时间以独立元数据块传给 Agent，不覆盖消息发送时间。若结构化屏幕已达到块数或字符上限，明确要求将确定时间作为独立线索补充，避免静默丢掉用户输入。

## 队列与恢复

- Room 升级至 v3，保留 v1→v2→v3 迁移。结构化证据、目标事项、固定任务请求 ID、服务端任务 ID 和错误信息落盘。
- 上传和任务创建分开记录：任务请求 ID 在任何网络调用前保存。上传成功、任务创建失败时，重试仍使用同一 ID 与同一材料版本。
- 每条材料均进入队列；不会因为上一条任务在运行而丢弃后续任务。后台只等待任务接收，不阻塞等待模型生成。
- 网络失败、5xx、429等暂时错误由 WorkManager 退避重试。明确的版本/契约/归属错误保留可见状态，供用户核对，不作为已完成处理。
- 去重按账号与事项隔离，只有本地证据和任务队列保存成功后才提交去重历史。已知消息时间进入指纹，避免跨日期相同措辞一律被吞掉；无时间、时间推断错误或 OCR 文本变化的边界仍需更多真机样例验证。
- 服务按包名/窗口类过滤、限频并检查窗口代次。账号或页面在截图过程中变化时丢弃该帧；保存前重新核对自动记录授权和账号。

## 模拟器测试方式

使用独立本机网关数据库和合成账号，不触碰公网用户数据。网关以 mock/structured/raw 运行，不加载 Qwen。模型生成能力与框架流程分别验收。

```powershell
# 构建与单元测试
./scripts/build.ps1 -Tasks @('assembleDebug','assembleDebugAndroidTest','testDebugUnitTest',':chronocore:testDebugUnitTest')
# 将模拟器8765端口映射到独立本机测试网关8876
./.tools/sdk/platform-tools/adb.exe -s emulator-5560 reverse tcp:8765 tcp:8876
# 安装应用与测试包后，运行专用用例
./.venv/Scripts/python.exe scripts/test-integration.py --serial emulator-5560
```

`IntegratedCaptureTest` 在模拟器中用真实 PP-OCR 识别合成聊天图片；测试控制器移除实际 TCP 转发，验证网络失败时 Room 保留证据与任务请求；恢复转发后补传并创建唯一任务，通过 Compose 页面确认候选，核对正式状态从 v0 到 v1，并保存截图。另有既有设备测试覆盖页面、账号隔离和数据库升级。

结果文件位于 `artifacts/emulator-integration/`。本轮实际执行结果见 `THREE_PART_TEST_REPORT.md`。模拟器的合成截图测试不等于已经在真实微信/QQ、不同 ROM、深色模式或群聊上完成联合验证。

## 部署顺序

先升级网关支持可选 `payload.screen`，再发布 v0.4 APP。旧网关会拒绝这个新增字段；单独改变地址不能完成适配。服务端继续使用外部模型 API 和轻量记忆，不增加云端 OCR 或 Qwen 权重。
