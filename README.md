# ScreenMind

端侧 OCR → 结构化信息过滤 → 云端 SuperAgent → 移动端交互体验。

## 三部分统一接入 · 手机 v0.4

`ChronoCapsule/core` 提供共享 PP-OCRv6 tiny、消息结构化与去重；`Mobile Client` 负责统一账号、上传授权、持久化队列和事项交互；`Cloud Agent` 复用通用执行与记忆。请完整检出三个目录后构建手机端。接口和模拟器测试见 [三部分集成说明](Mobile%20Client/docs/THREE_PART_INTEGRATION.md)。

结构化材料通过带登录的 `/v1/events` 上传，再通过 `/v1/agent/tasks` 进入明确选择的事项，使用 cloud-v1 确认流程。ChronoCapsule 独立调试 APP 的 `/api/*` 地址不作为公网手机入口。服务端须先升级支持 `payload.screen`，再安装手机 v0.4。

## 云端 Agent

云端实现位于 [Cloud Agent](Cloud%20Agent/README.md)，包含服务代码、可注册 SubAgent、事项状态与记忆、调试界面、接口文档和测试。

进入目录后按说明安装并启动：

```powershell
cd "Cloud Agent"
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\run.ps1
```

默认使用 mock。真实模型配置请参考目录内 `.env.example`，在本地填写密钥。

- [当前 API 契约](Cloud%20Agent/docs/api-contract.md)
- [SubAgent 扩展](Cloud%20Agent/docs/subagents.md)
- [云端实施路线与拟议协议](Cloud%20Agent/docs/cloud-roadmap.md)

## 安卓客户端与云端联调

手机 v0.3 增加应用内更新；部署后通过固定 HTTPS 地址连接 Agent、检查版本和下载安装包。参见 [服务器配置与部署](Mobile%20Client/docs/SERVER_DEPLOYMENT.md) 和 [更新发布流程](Mobile%20Client/docs/APP_UPDATES.md)。

[Mobile Client](Mobile%20Client/README.md) 提供原生安卓界面、端侧中文 OCR、账号与测试组、线索同步和云端事项交互。手机通过原有认证网关的 `/v1/agent` 接口使用同一套 Cloud Agent 引擎；每位用户的事项、证据、索引和动作文件分别隔离。

手机联调只需启动一个服务，默认端口为 `8765`：

```powershell
cd "Mobile Client"
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
```

首页进入“云端事项”，选择已同步线索，再依次处理澄清、确认方案、单独授权动作。当前手机端支持 Information、Party 和 Schedule；日历动作生成可保存的 ICS 文件，文件保存与日历导入分别展示。原有规则演示保留为独立入口。

云端 v0.5 增加轻量 SQLite 历史检索、结构化记忆/意图规划实验及模型证据审阅辅助 Agent；保留 SimpleMem 作者文本适配、LanceDB 与 Qwen 接口。源码默认 basic，部署配置可选择不加载向量模型的 structured 模式。手机网关不开放共享服务器凭据的 CalDAV 写入。研究范围和实验结果见 [复现记录](Cloud%20Agent/docs/research-reproduction.md)。

- [端云接口、隔离与启动说明](Mobile%20Client/docs/AGENT_INTEGRATION.md)
- [本轮验证记录](Mobile%20Client/docs/AGENT_TEST_REPORT.md)
- [增强能力安装与验证边界](Cloud%20Agent/docs/phase-two.md)

如同时运行独立云端网页工作台，请把它设到另一个端口（例如 `8766`）。工作台自己的数据库与手机账号工作区分开。完整检出两个目录后再启动手机网关，不使用旧版单独的手机源码包。
