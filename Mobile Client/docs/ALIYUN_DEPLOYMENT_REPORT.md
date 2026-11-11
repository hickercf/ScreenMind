# 阿里云原生部署验收 · 2026-11-11

## 部署结果

- 入口：`https://agent.qwertyuiopasdf.asia`，首页跳转到 `/download`。
- Ubuntu 24.04 x86_64，标称 2 核 / 2 GiB / 40 GiB；系统报告内存约 1613 MiB。
- 使用 Nginx、systemd 和独立 Python 3.12 虚拟环境；一个 Uvicorn worker。
- Cloud Agent 0.4.0，真实模型 `deepseek-flash`，`openai-compatible` 提供者。
- basic 记忆，raw 提取器，每个用户运行实例的任务并发为 1；未加载 Qwen、LanceDB 或 SimpleMem。
- APP 分发版本 0.3.0 / versionCode 3。安装包大小 107904810 字节。
- HTTPS 证书已签发，有效期至 2027-02-09，Certbot 续期定时器已启用。

## 实际验证

| 验证项 | 结果 |
|---|---|
| 服务配置 | `systemd-analyze verify`、`nginx -t` 通过 |
| 依赖完整性 | 保持原锁定版本；Linux wheel 与 PyPI 官方 SHA256 核对；`pip check` 通过 |
| 公网 `/health` | HTTP 200 |
| 公网 `/download` | HTTP 200 |
| 首页 `/` | HTTP 302 跳转 `/download` |
| 未登录 `/v1/agent/health` | HTTP 401 |
| 注册、退出及再次登录 | 通过，使用临时合成账号 |
| 登录后 Agent 健康信息 | `provider=openai-compatible`、`model=deepseek-flash`、`memory_mode=basic` |
| 真实模型日程整理 | 合成中文日程生成 `state_proposal`，单次轮询观测约 4.17 秒 |
| 用户确认 | 正式状态版本增至 1 |
| 外部动作授权 | `calendar.export` 成功，ICS 包含 VEVENT；未向手机系统日历写入 |
| 旧版更新检查 | versionCode 2 收到 HTTP 200，指向 0.3.0 |
| 同版更新检查 | versionCode 3 收到 HTTP 204 |
| 安装包完整性 | 上传后在服务器校验完整 SHA256 通过 |
| 公网安装包 Range | HTTP 206，读取 1024 字节，Content-Range 总长一致，ZIP 文件头正确 |
| 服务重启 | systemd 重启成功，事项库仍存在且 SQLite 完整性检查通过 |
| 备份恢复 | 停服备份后恢复至独立临时目录，两份 SQLite 完整性检查通过 |
| 自动启动 | `screenmind` 服务与 `certbot.timer` 已启用 |
| 测试数据清理 | 合成账号及其独立事项目录已清理，未保留活跃测试会话 |

安装包 SHA256：`3af162de9140d0a08fbbe434b52f68628f4db8e471cd4360878c6530aa64845c`。

`/health` 中的 `decision_provider=rule_demo` 描述旧版兼容接口，不表示 `/v1/agent` 正在使用模拟模型；真实提供者已通过登录后的 Agent 接口及模型任务验证。

## 资源与边界

旧业务停用后，重启验收时系统可用内存约 1062 MiB；服务空闲内存约 36 MiB。这是单次部署观测，不是峰值压测或并发容量保证。未做整机重启测试，已验证服务重启和开机启用状态。

原站点 Nginx 配置已备份并停用，小说服务及已识别的动画站点进程、旧 Tomcat 容器已停止；没有重装系统，也没有删除整盘旧数据。原有系统管理组件和网络代理容器保留。

本机移动网关检查到 0 个账号、0 个事项数据库，因此没有用户数据迁移。手机改服务地址后仍按地址隔离本地缓存，不能将此部署描述为完成了旧客户端缓存迁移。

尚未验证真实手机安装、移动网络登录和应用内更新安装；公网 APK 只做了分段下载验证，完整文件校验在服务器端完成。未启用增强记忆、未压测、未验证 Docker Compose 方案。

维护与后续发布见 [原生部署说明](../deploy/native/README.md)。部署密钥、模型密钥、数据库与安装包不在源码仓库中。
