# 云服务器与手机连接 · 2026-11-10

## 推荐配置

当前手机通过认证网关 `/v1/agent` 使用 Cloud Agent，大模型通过外部 API 调用，OCR 在手机运行。服务器无需部署 DeepSeek 权重，也不需要 GPU。

| 用途 | CPU / 内存 | SSD | 说明 |
|---|---|---|---|
| 最小联调 | 2 核 / 4 GB | 50 GB | basic 记忆、小规模测试；不在此配置上承诺本地 Qwen 多用户服务 |
| 推荐起步 | **4 核 / 8 GB** | **80 GB** | API 模型、basic 记忆、账号与事项数据库、更新接口 |
| 后续本地向量模型 | 4–8 核 / 16 GB | 100 GB | 先改进编码器复用，再测 Qwen 冷启动、中文检索时延与峰值内存 |

以上为工程预算建议，尚未在云主机上压测，不代表并发或吞吐保证。推荐 Ubuntu 24.04 LTS x86_64，公网 IPv4，能够访问配置的模型 API。先按月购买可升级实例；模型 API 额度、域名及对象存储另算。

当前网关每进程最多打开 **8 个用户工作区**，不是 8 个请求并发；暂未自动回收工作区。使用 **1 个 Uvicorn worker**，不能通过直接加 worker 来扩容相同数据库上的任务执行。Qwen 编码器当前按用户运行实例创建；后续共享推理服务需保留按用户隔离的检索索引。购买更多内存不会自动解决这些架构限制。

公网带宽可从 5 Mbps 起步测试普通 API，但应用安装包约 108 MB；按 5 Mbps 独占理想下载约 3 分钟，多人下载会分享出口。建议后续把 APK 放 HTTPS 对象存储/CDN，云服务器仅返回版本信息与处理 Agent 请求。更新机制已经支持这种分工。

## 部署方式

目录 `deploy/` 提供 Docker Compose：认证网关与 Cloud Agent 在同一 Python 进程运行，Caddy 提供 HTTPS；账号库、事项与动作文件放入持久卷 `agent_data`，更新包从 `backend/releases` 只读挂载。

本方案默认 basic 记忆。现有增强记忆代码仍保留，但这个部署镜像不安装 Qwen/LanceDB 大依赖；需要增强记忆时再增加可选依赖并验证资源使用。

准备服务器后：

1. 安装 Docker Engine 和 Compose 插件，完整检出 ScreenMind 仓库，保持 `Cloud Agent` 和 `Mobile Client` 相邻。
2. 将自有域名解析到服务器公网 IP。开放 TCP 80、443；SSH 仅向自己的管理来源开放。无须向公网开放 8765。
3. 在 `Mobile Client/deploy` 复制 `.env.example` 为 `.env`，填真实域名、模型地址、模型名和密钥。密钥仅保存在服务器，不能填进 APP 或提交 Git。配置文件权限设为仅管理员可读。
4. 创建 `Mobile Client/backend/releases`，将已准备好的安装包与版本清单放进去。也可以先留空，仅启动 API。
5. 从 `Mobile Client/deploy` 执行：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

`config --quiet` 只验证配置，不把包含密钥的展开结果打印出来。镜像构建不会复制本地 `.env`、数据、开发工具或安装包；模型参数在运行时注入。

Caddy 会为配置的公网域名申请并续期证书，要求 DNS 正确、80/443 可达、证书存储持久化；参见 [Caddy 官方说明](https://caddyserver.com/docs/automatic-https)。Compose 的环境变量配置参见 [Docker 官方说明](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。

## 手机怎么连接

例如自有域名为 `agent.example.com`（仅示例）：

- 手机浏览器访问 `https://agent.example.com/download` 下载首次安装包。
- APP 登录页“服务连接”或右上“更新 → 设置服务地址”填 `https://agent.example.com`，不要添加 `/v1`、模型服务地址或 API 密钥。
- 注册/登录账号；上传前核对端侧 OCR 文本，然后首页进入“云端事项”。
- 同一地址提供版本检查和新版下载，后续无需人工传包到手机。

手机上的 `127.0.0.1` 指手机自身，只有 USB 调试端口转发时才使用本地联调地址。换到远程地址后，旧地址下的账号和本地记录仍按原服务隔离，不会自动迁移到云端。

云端代码更新后重建 gateway 即可；无手机端改动时不需要发布 APK。手机端代码更新才需要提高 `versionCode`、重新构建并发布，见 [APP 更新说明](APP_UPDATES.md)。

## 持久化与验证边界

- 升级服务使用 `docker compose up -d --build`；不要使用 `docker compose down -v` 删除持久卷。
- 备份时先停止 gateway，再备份完整 `agent_data` 卷（含 SQLite 相关文件），随后启动 gateway，避免只拷贝正在写入的数据库主文件。实际恢复演练在服务器交付时进行。
- 部署后验收 `/health`、注册登录、`/v1/agent/health`、一次事项确认与 ICS 导出，以及手机更新下载安装。
- 目前没有云服务器与域名可部署；Compose 已做配置解析验证，本机 Docker 引擎未运行，尚未验证 Linux 镜像构建、真实域名 HTTPS 或服务器性能。不能把部署模板等同于已上线服务。
