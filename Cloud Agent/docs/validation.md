> 最新 v0.3 第一阶段验证见 [phase-one-validation.md](phase-one-validation.md)。以下为原开发环境历史记录。`artifacts/` 中的报告、截图和临时数据库不随源码发布，可按对应脚本重新生成。

# 真实模型接入验证 · 2026-11-08

服务：`https://maas.whu.edu.cn/v1`；配置模型：`deepseek-flash`；Provider：`openai-compatible`。只向该服务发送本次合成测试的聊天和计划数据。

首先验证真实响应包含合法的 `read_screens` 工具调用，再运行：

```powershell
.\.venv\Scripts\python.exe -u scripts/live-model-smoke.py --run --db data/real-model.sqlite3
```

实际结果：**通过**。使用自然语言消息，不使用 Party mock 固定格式解析。

| 场景 | 检查结果 | 本次耗时 |
| --- | --- | --- |
| 初始聚会 | 正确提取 8 人、整桌 ¥688、人均预算 ¥150、小林负责蛋糕 | 5.53 秒 |
| 模糊退出 | “可能赶不过来，还没确定”标记 tentative；取消后记忆不变 | 5.94 秒 |
| 明确退出 | 7 人、约 ¥98.29/人、蛋糕责任缺口；仍保留已确认旧计划 | 5.12 秒 |
| 自然语言重新分工 | 小王接手蛋糕，小林仍退出，确认后记忆 v2 | 4.30 秒 |

四轮均未发生工具参数拒绝。上述耗时仅是本次测试观测，包含模型/工具往返和本地轮询，不是延迟保证或性能基准。

最终 **31 项后端回归测试通过（8.07 秒）**，新增本机配置读取、环境变量覆盖、字面量不执行、密钥不进入配置 repr 与错误信息的检查。回归测试使用 mock 或 HTTP MockTransport，不会调用真实服务。

工作台已实际显示“真实模型 · deepseek-flash”、已完成任务和已确认记忆 v2；浏览器无页面 JavaScript 异常。

证据：`artifacts/real-model-report.json`、`artifacts/real-model-browser-report.json`、`artifacts/real-model-desktop.png`、`artifacts/test-results.xml`。密钥只在已忽略的本机 `.env` 中，不记录到测试脚本或报告。

这次验证覆盖一个聚会的代表性变化，不替代复杂聊天、冲突信息、OCR 错误、多轮长期上下文的评估；Android 仍未联调。

---

# v0.2 框架与 Party 验证记录

验证日期：2026-11-08（客户端项目日期）。Windows、项目独立虚拟环境 Python 3.12.14、Node.js 24.14.0、隔离无头 Chrome。浏览器验收数据库为 `data/party-check.sqlite3`。

## 后端最终回归

```powershell
.\.venv\Scripts\python.exe -u -m pytest -q --basetemp=artifacts/pytest-run-08 --junitxml=artifacts/test-results.xml
```

**29 passed in 7.93s**。原有 18 项测试保留，新增 11 项验证：

- Party 初始草稿不写记忆；确认后保存 v1。
- 小林退出，8 人变 7 人，¥688 固定费用对应 ¥86.00 → 约 ¥98.29/人，蛋糕责任缺口。
- 通过用户补充重新分配给小王，卡片保留 input 来源；重复确认不重复增加记忆版本。
- “可能来不了”是 tentative，不是 declined；取消草稿不改变已确认状态。
- 并发任务旧记忆版本确认返回 409；重新生成后保留其他任务已确认的变化。
- 两个聚会的成员与记忆隔离；禁止屏幕跨事项读取；错误能力绑定被拒绝。
- 服务重启后继续读取已确认计划。
- 注册第三个测试 SubAgent，不修改 Router/Worker/Store 即可运行并确认记忆。
- v0.1 数据库自动增加字段且保留旧任务；旧请求的幂等记录兼容新增可空字段。
- 无法识别的退出成员明确失败；全部成员退出时不除以零。
- 模型伪造另一聚会的 memory 引用被拒绝，不能发布卡片或写记忆。

测试环境有 1 条 Starlette 关于未来 TestClient HTTP 客户端迁移的弃用提示，不影响本次通过结果。真实 Provider 测试仍使用 HTTP MockTransport。

## 浏览器最终回归

在最终后端上依次运行 `scripts/browser-smoke.cjs` 和 `scripts/party-browser.cjs`，**两者全部通过**。

Party 实际操作覆盖：新建独立事项、自动按事项分发、确认初始计划、载入退出消息、检查费用和责任缺口、验证草稿与已确认记忆分离、派生字段只读、补充责任人、确认 v2、刷新恢复。

桌面 1440px 与手机 390px 均已截图并检查，手机无横向溢出，两个流程均无页面 JavaScript 异常。原信息整理的字段编辑、脏输入确认拦截与刷新恢复也保持通过。

结果与截图：

- `artifacts/test-results.xml`
- `artifacts/browser-report.json`
- `artifacts/party-browser-report.json`
- `artifacts/party-departure-desktop.png`
- `artifacts/party-mobile.png`

另通过 Python compileall 与两个 JavaScript 文件的语法检查。

## 当前验证边界

这是默认 mock 模式的可运行框架与领域规则验证。尚未用真实密钥验证任意自然语言、复杂聊天冲突、真实 OCR 或 Android 原生组件。记忆是按事项隔离的结构化状态与版本历史，尚无向量检索。当前是单工作区、单进程服务，没有外部发送/预订行为。

---

# v0.1 历史验证记录

验证日期：2026-11-07。环境：Windows、Python 3.12.5、Node.js 24.14.0、独立无头 Chrome。

## 后端

命令：

```powershell
python -u -m pytest -q --basetemp=artifacts/pytest-run-05 --junitxml=artifacts/test-results.xml
```

结果：**18 passed in 6.04s**。覆盖：

- 两份屏幕 → 建任务 → 工具调用 → 卡片 → 字段修订 → 继续 → 确认。
- 上传屏幕不会自动建任务。
- 屏幕、任务、动作幂等，以及相同 ID 不同内容的冲突。
- 过期 revision / card_version 被拒绝。
- 运行中补充输入使旧轮次失效，取消后不会发布旧结果。
- 重启恢复 running 任务；已完成任务不重跑。
- 四个任务的并发上限及上下文隔离。
- 伪造证据引用被拒绝，执行步数耗尽后明确失败。
- 失败重试、任务超时、模型 HTTP 重试上限。
- JSON、时区、请求大小、Host、Origin、API Key 边界。
- 工作区查询隔离、数据库单进程锁。
- 真实模型适配器的请求格式、工具往返和错误信息脱敏。
- 最大长度 block ID、新屏幕追加。

真实模型适配器测试使用 HTTP MockTransport，不涉及实际模型推理或真实密钥。

测试中发现并修复 Windows 对锁定文件字节读取的限制；现在重复调度器启动会返回明确的运行错误。

## 浏览器操作

脚本：`scripts/browser-smoke.cjs`。服务使用单独的 `data/ui-check.sqlite3` 数据库。

- 载入两份合成屏幕。
- 创建任务并等待真实工具事件产生。
- 编辑一个字段、提交补充要求，生成 v2 卡片。
- 验证用户修订值及“用户修订”标记保留。
- 验证未保存内容不能直接确认丢弃。
- 确认完成、刷新页面，验证任务状态恢复。
- 桌面 1440px、手机 390px 布局；手机无横向溢出。
- 未捕获到页面 JavaScript 异常。

截图位于 `artifacts/console-empty.png`、`artifacts/console-desktop.png`、`artifacts/console-mobile.png`；机器可读结果位于 `artifacts/browser-report.json`。截图已人工查看，修正了侧栏定位和手机标题布局。

## 本机 HTTP 联调

`python scripts/demo.py` 已实际调用运行服务，返回 completed、card_version=2、edited_value_retained=true。

SSE 已实际使用 `Last-Event-ID: 1` 连接，首个返回事件为 seq=2，验证断点后的事件投递。

## 未验证的部分

- 没有真实模型密钥，尚未验证真实模型的字段理解、推理准确率、费用和延迟。
- 没有 Android 工程，尚未联调真实 OCR、手机后台采集和原生卡片组件。
- 没有进行生产压力测试、多用户认证或跨进程部署验证。
- 不宣称实现了完整 DeepSeek Super-agent，也不宣称达到原方案中的毫秒级性能目标。
