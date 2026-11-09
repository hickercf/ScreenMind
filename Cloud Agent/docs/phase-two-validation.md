# v0.4 验证记录

日期：2026-11-09。开发、安装依赖与测试均在 `ScreenMind/Cloud Agent`，使用本目录独立 `.venv`，没有修改原 `superagent` 源码。本轮真实模型脚本读取原目录的本地配置，不把密钥写入代码或报告。此记录描述云端阶段的独立验证，后续手机网关联调及回归见 [端云验证记录](../../Mobile%20Client/docs/AGENT_TEST_REPORT.md)。

## 自动化回归

最终：**59 passed，17.24秒，无跳过**。包含原43项和本轮新增16项。命令：

```powershell
.\.venv\Scripts\python.exe -m pytest -q --basetemp=artifacts/pytest-phase2-final-02 --junitxml=artifacts/phase-two-tests.xml
```

测试包含真实 LanceDB 文件操作和固定上游 SimpleMem 提示/解析适配；LLM 大部分使用 mock，CalDAV 使用 HTTP MockTransport。不能把测试通过解释成59次真实模型验证。

新增覆盖：

- 日程初始状态、改期依赖冲突、澄清恢复、用户完成反馈、明确确认。
- 较早/重复通知不回退正式状态，未知消息时间与空时间澄清。
- 授权前无文件、授权后实际 ICS 文件、幂等回放、下载。
- 正式版本变化拒绝执行，取消动作，未知结果先核对且不重发。
- 重启恢复执行中动作至 unknown，核对已有文件后完成。
- 多个预览无法重复授权同一条目与版本。
- CalDAV 条件 PUT、412 后 GET 核对、目标集合变更拒绝旧授权。
- 辅助委派限定来源、无正式状态写入、幂等、超时、取消、版本过期结果丢弃。
- 从真实 Worker 工具循环完成一次辅助委派，再发布候选而不直接提交。
- 真实 LanceDB 检索超出近期历史窗口的材料、事项隔离、删除后重建不恢复来源。
- 删除与记忆构建并发时，迟到结果不能恢复已删除材料。
- 抽取/向量检索失败使用原文补齐和基础路径；缺少 Qwen 权重时服务仍可运行。
- 实际 SimpleMem 上游对象每次独立，第二次材料不混入第一次缓存。

保留两个依赖弃用提示：Starlette TestClient 的 HTTP 客户端提示；SimpleMem 上游 Pydantic `Config` 写法提示。没有为消除提示而替换尚未验证的依赖组合。

## 实际浏览器与 HTTP

使用本机 FastAPI 服务、Edge 无界面浏览器、真实 HTTP、独立数据库与索引；任务模型 mock，增强检索配置 raw + hash-demo + LanceDB。

三个脚本均通过：

| 脚本 | 验证 |
| --- | --- |
| `phase-two-browser.cjs` | 日程示例、v1状态确认、动作预览、单独授权、实际 ICS 下载、390px无横向溢出、无页面错误 |
| `party-browser.cjs` | 初始8人、退出后7人、86→98.29元、责任缺口、重新分工、v2提交、刷新持久化、手机宽度 |
| `browser-smoke.cjs` | 原信息整理、字段修订、继续处理、未保存修改阻止确认、完成、刷新与手机布局 |

期间发现动作 JSON 预览会撑宽手机页面，已修复文本换行并复测。测试导出了真实文件，但**没有导入用户日历**。

本地证据：`artifacts/phase-two-browser.json`、`party-browser-report.json`、`browser-report.json`、`phase-two-desktop.png`、`phase-two-mobile.png`、`browser-calendar.ics`。这些测试产物被 Git 忽略。

## 真实模型：SimpleMem

服务：用户已配置的武汉大学 MaaS，模型 `deepseek-flash`。上游提交 `db80b6a7c591e0ea730a058e9f5fc4eb06572299`。只使用合成的“甲场地6座、聚会8人、因此未选择”文本。

- 最终单条抽取通过，耗时9.58秒，得到非空结构化复述、关键词、人物与实体。
- 一次中间复测出现模型输出截断；已增加截断检测并调整为4,096 token上限，失败输出不进入索引。没有声称该模型每次输出都有效。
- 真实组合联调通过：SimpleMem真实模型抽取 → LanceDB实际索引 → 框架检索 → 回查SQLite原文。关闭近期历史补充后，仍找到“只能坐6人”的原文；正式状态版本保持0。
- 该组合使用 **hash-demo-256 工程编码器**，构建18.69秒，本次检索31毫秒。仅一个来源，不是延迟基准或语义召回评测。

报告：`artifacts/simplemem-live-check.json`、`artifacts/memory-live-check.json`。复现脚本分别是 `check-simplemem.py`、`check-memory.py`，必须显式传入 `--run`。

## 真实模型：Schedule

`check-schedule.py` 用同一真实模型和合成自然语言通知完成：

1. 初始材料17点、答辩18点，明确材料须早于答辩，生成候选：4.91秒。
2. 答辩提前至16点，系统发布澄清，未直接改正式状态：4.72秒。
3. 用户回答材料改15点、答辩16点，生成新候选：5.33秒。
4. 明确接受后，正式状态为15点/16点，版本从1到2。

报告：`artifacts/schedule-live-check.json`。这是单次三阶段合成场景验证，不是自然语言理解准确率。

## Qwen 与外部账号尚未完成的验证

**Qwen 适配、依赖和固定版本下载/编码检查脚本已实现，真实权重加载与语义编码尚未验证成功。**

本轮从官方 Hugging Face 固定提交下载权重，两次普通 HTTP 下载遇到连接中断/读超时；安装官方 hf-xet 1.8.0 后尝试分块下载，仍未在本轮窗口内完成，已停止后台下载。没有生成成功的 `qwen-check.json`，不把词法哈希向量称为 Qwen，也不声称已验证其召回效果。用户网络可用时重新执行 `prepare-qwen.py` 即可继续验证。

**CalDAV 代码进行了模拟协议测试，没有使用真实日历账号。** 需要提供测试集合和账号凭据后，单独验收服务兼容性及真实落地记录。当前 ICS 导出、下载与重启核对已经执行。

尚未执行冻结测试集上的端到端准确率、跨业务消融、完整历史基线、总 token/成本比较、长时间压力和 Android 真机 OCR 联调。增强记忆默认关闭；这些验证完成前不宣称业务收益、生产可用性或比赛指标达标。
