# 端云集成验证 · 2026-11-09

范围：Android v0.2 + 手机认证网关 + Cloud Agent v0.4。以下样例均为合成数据，不代表真实用户试用或比赛准确率。旧 `TEST_REPORT.md` 和截图是手机 v0.1 的历史记录，不用于声称本轮页面已在设备上通过。

## 已完成验证

| 项目 | 结果与证据 |
|---|---|
| 手机旧接口 + 新 Agent 网关测试 | 19 项通过。使用新建的 `.venv`，按 requirements.lock.txt 安装；最终回归 13.590 秒，本地 `artifacts/gateway-final-tests.txt` |
| Cloud Agent 回归 | 59 项通过，17.41 秒；本地 JUnit 报告 `Cloud Agent/artifacts/mobile-integration-cloud.xml` |
| 启动脚本 + 真正 HTTP 连接 | Windows PowerShell 启动成功；HTTP 注册、会话访问 `/v1/agent/health` 成功。本地 `artifacts/http-startup-report.json` |
| 真实模型网关闭环 | MaaS / deepseek-flash，自然语言 Schedule 输入生成候选（本次 4.59 秒），确认后 state v1，独立授权生成 ICS，action succeeded。本地 `artifacts/agent-integration-report.json` |
| Android 调试应用与设备测试包 | `assembleDebug`、`assembleDebugAndroidTest` 成功，应用版本 0.2.0 / versionCode 2 |
| Android 本地单元测试 | ContractTest 3 项通过，0 失败；`app/build/test-results/testDebugUnitTest/TEST-cn.weiguang.app.ContractTest.xml` |
| Android Lint | 0 错误、16 项非阻断提示，报告 `app/build/reports/lint-results-debug.html` |

最终 Android 命令为 `& ./scripts/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','lintDebug','assembleDebugAndroidTest')`，结果 `BUILD SUCCESSFUL in 1m 55s`（此前冷启动下载依赖的耗时不计入此值）。本地日志 `.tools/build-verified.log`。安装包为 `app/build/outputs/apk/debug/app-debug.apk`，测试包为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，使用开发调试签名。

修复了 Windows 中文项目路径导致测试类无法加载的问题，构建脚本用临时空闲盘符映射并在结束时清理；也修正了新页面直接读取 StateFlow.value，改为生命周期订阅。Lint 剩余提示为既有依赖更新、SharedPreferences/KTX 风格和 ARM64 pilot 对 ChromeOS 的 ABI 提示，本轮未以升级全部依赖来扩大改动。

网关测试具体覆盖：无会话拒绝访问、账号之间事项/任务/文件不可见、拒绝伪造归属字段、创建幂等、来源版本一致性、更新旧线索使候选失效、拒绝旧来源重试且调度器仍可用、删除队列跨重启恢复、回答澄清后继续同一任务、确认与动作授权分离、重复授权不重复执行、长文本无截断、发生时间不冒充消息时间。

真实模型样例没有使用用户真实截图或私人日历，没有进行 CalDAV 写入。4.59 秒是一次样例的观测值，不是性能评估。导出成功不是手机保存或系统日历导入成功。

## 重复验证

在 `Mobile Client` 目录执行：

```powershell
.\.venv\Scripts\python.exe -m unittest backend.test_api backend.test_agent_bridge -v
.\.venv\Scripts\python.exe scripts/check-agent-integration.py
# 显式允许真实模型调用，默认从 Cloud Agent/.env 读取：
.\.venv\Scripts\python.exe scripts/check-agent-integration.py --real --run
```

模拟/真实 smoke 各创建独立的 artifacts 数据库和随机账号，报告会覆盖本地最近一次结果。不要把数据库或模型密钥提交仓库。

设备测试新增 `AgentFlowTest`：选择授权线索→创建 Schedule→提交任务→确认前 v0→点击确认后 v1，以及 Room 1→2 迁移保留原文、待重试请求持久化和账号隔离。与原 4 个测试一起运行时共 6 项，需网关 mock/basic、设备端口转发。当前没有连接的 Android 设备，**本轮设备执行尚未完成**；测试源码不能替代执行证据。

设备测试网关启动前设置 `$env:SCREENMIND_PROVIDER='mock'`。新测试在提交任务前核对实际 provider，防止误用真实模型产生调用；退出测试后清除该进程配置或重新打开终端再运行真实模型。

## 待设备验收

1. 在目标手机导入中文聊天截图，核对 OCR、小字/滚动/混排文本。
2. 实际操作新事项页面，验证澄清、拒绝、修订、重新进入任务及来源查看。
3. 在请求提交后断网或杀进程，恢复后确认同一幂等请求不产生重复事项/任务/动作。
4. 升级安装 v0.1 的已有数据，确认 Room 迁移及账号切换；新登录后不读取其他账号的材料。
5. 保存 ICS、由用户导入目标日历应用，再核对标题和时区。未接入每账号 CalDAV，不把这些测试归为自动日历写入。

增强记忆、真实 embedding、辅助委派和独立 Cloud CalDAV 的历史验证边界见 `Cloud Agent/docs/phase-two-validation.md`。本次手机真实模型验证使用 basic，没有新增 Qwen 权重执行或中文检索质量证据。
