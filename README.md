# ScreenMind

端侧 OCR → 结构化信息过滤 → 云端 SuperAgent → 移动端交互体验。

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

## 安卓客户端

[Mobile Client](Mobile%20Client/README.md) 包含原生安卓界面、端侧中文 OCR、测试账号与组管理，以及本地联调服务。客户端目前使用自己的 `/v1` 联调接口，接入 `Cloud Agent` 的 `/api` 任务接口是下一阶段工作。两个服务默认都使用端口 `8765`，在同一台电脑上测试时请分别启动。
