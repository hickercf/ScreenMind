# Ubuntu 小内存服务器部署

适用于 Ubuntu 24.04 / Python 3.12，以 systemd 管理一个 Uvicorn 进程，复用 Nginx 提供 HTTPS。OCR 在手机端执行，模型调用外部 API；记忆使用 basic 模式，不安装 Qwen、PyTorch 或 LanceDB。

2 核 / 2 GB 可作为小规模试用配置，实际容量须测量；它不等同于高并发部署方案。服务设置 700 MB 的 MemoryHigh 和 1 GB 的 MemoryMax，达到上限可能触发回收或重启，应根据真实负载监控调整。每进程最多打开 8 个用户工作区的现有限制仍然存在。

## 目录与权限

| 路径 | 用途 |
|---|---|
| `/opt/screenmind/app` | 完整仓库，保持 Cloud Agent 与 Mobile Client 相邻 |
| `/opt/screenmind/venv` | 独立 Python 3.12 虚拟环境 |
| `/etc/screenmind/screenmind.env` | 运行配置，root:screenmind，权限 640 |
| `/var/lib/screenmind` | SQLite、事项、动作文件，screenmind 用户可写 |
| `/var/lib/screenmind-releases` | APK 和更新清单，服务只读 |
| `/var/www/screenmind-acme` | 证书验证目录 |

使用专用系统用户 `screenmind`（不可交互登录）。项目源码和虚拟环境由管理员维护，运行用户只需读取。密钥不能写入仓库或安装包。

## 安装与启动

先备份既有站点配置并确认 80/443 的使用情况，不要直接覆盖其他站点。完整上传源码后，在独立虚拟环境安装：

```bash
python3 -m venv /opt/screenmind/venv
/opt/screenmind/venv/bin/pip install -r '/opt/screenmind/app/Mobile Client/backend/requirements.lock.txt'
/opt/screenmind/venv/bin/pip install --no-deps -e '/opt/screenmind/app/Cloud Agent'
/opt/screenmind/venv/bin/pip check
```

按 `screenmind.env.example` 创建 `/etc/screenmind/screenmind.env`，在服务器填入真实模型密钥。复制 `screenmind.service` 到 `/etc/systemd/system/` 后：

```bash
sudo systemd-analyze verify /etc/systemd/system/screenmind.service
sudo systemctl daemon-reload
sudo systemctl enable --now screenmind
curl --fail http://127.0.0.1:8765/health
```

将 `nginx-http.conf` 安装为 Nginx 站点并启用。模板域名为 `agent.qwertyuiopasdf.asia`，其他部署须替换。A 记录指向服务器、80/443 放行后运行：

```bash
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d agent.qwertyuiopasdf.asia --redirect
sudo systemctl status certbot.timer --no-pager
```

8765 只监听本机，无需对公网开放。站点首页跳转 `/download`；APP 服务地址填写 `https://agent.qwertyuiopasdf.asia`，不加接口路径。

## 更新与备份

后端更新：先备份数据、更新源码与依赖，再 `sudo systemctl restart screenmind`，检查健康接口及一次实际任务。APK 没有改动时无需重新发布 APP。

APK 更新：保持原签名并递增 versionCode，用 `scripts/prepare-update.py` 生成清单；先上传 SHA256 命名的 APK、校验完整哈希，最后原子替换 `update-debug.json` 或 `update-pilot.json`。不能让清单指向未传完的文件。

备份应在服务停止后复制完整数据目录，随后恢复服务：

```bash
sudo systemctl stop screenmind
sudo tar -czf /var/backups/screenmind-data.tgz -C /var/lib screenmind
sudo chmod 600 /var/backups/screenmind-data.tgz
sudo systemctl start screenmind
```

备份覆盖前应保留历史版本；配置文件中的密钥另行安全备份。检查 `systemctl status screenmind`、`journalctl -u screenmind`、`free -m` 和 `df -h`。单机磁盘上的备份不能替代异地备份。

## 验收

- 公网 HTTPS `/health` 返回 200，未登录 `/v1/agent/health` 返回 401。
- 注册、登录后，Agent 健康接口显示真实模型提供者。
- 合成日程输入经过真实模型形成候选；用户确认后版本递增；授权后可导出 ICS。
- `/download` 有安装包，旧版本检查返回更新，同版本返回 204，下载文件大小和 SHA256 一致。
- 服务重启后数据保留，systemd 开机启动和证书续期定时器已启用。
- 手机真实安装、登录和更新需另外验收；命令行成功不等同于完成手机端验证。
