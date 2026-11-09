# 微光 · Android 端侧信息助手

首发平台为 Android 10 及以上，使用 Kotlin、Jetpack Compose、Room 和内置中文 OCR。手机端可以离线运行，测试账号和组管理由随工程提供的联调后端实现。

## 已实现的第一版

- 森林绿视觉风格、深浅主题、首页、线索、设备、我的四个入口。
- 真实账号注册、登录、令牌刷新、退出和加密会话保存。
- 创建测试组、邀请码入组、查看成员、组长移除成员、明确共享和撤回共享。
- 从相册选择图片，或从其他应用分享图片到微光，在端侧识别中文并纠错。
- 读取用户选择的系统日历记录、单次大致位置采样、手动记录。
- 按账号与服务地址隔离本地数据，上传队列、断网重试、版本检测和删除同步。
- 带来源依据的摘要与追问卡片、采纳及忽略反馈。
- 手机通知测试，供指定手表或手环验证通知转发。

当前建议使用 `rule_demo` 规则提供者，界面明确显示“规则演示”。真实 AI 模型、QQ/微信后台通知采集、连续屏幕采集、穿戴健康 SDK 和腕上按钮回传属于队友后续对接范围。原始截图只在导入核对时使用，不上传和长期保存。

## 快速启动

已准备便携工具、模拟器和调试 APK 的 Windows 电脑可以双击本目录的 `START-PC-TEST.bat`。它会启动本地后端、打开安卓模拟器、安装通用调试 APK 并打开微光。全新检出可用 Android Studio 打开本目录，按下文启动联调后端后运行 `debug` 构建。

可以先选“先离线体验 OCR 与页面”，或自行注册账号来测试建组和云端联调。模拟器默认服务地址为 `http://127.0.0.1:8765`，启动脚本已配置转发。关闭模拟器窗口即可结束手机体验；本地后端继续运行供下次使用。启动失败时日志在 `.tools/logs`。

在仓库根目录打开 PowerShell。

```powershell
Set-Location '.\Mobile Client'
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
```

脚本首次创建 `.venv` 并安装后端依赖，精确版本记录在 `backend/requirements.lock.txt`。服务默认只监听本机 `127.0.0.1:8765`。浏览器打开 `http://127.0.0.1:8765/docs` 可查看和调试接口，`/health` 用于检查服务状态。

连接安卓手机并开启 USB 调试后，在另一个终端执行。

```powershell
& '.tools/sdk/platform-tools/adb.exe' devices
& '.tools/sdk/platform-tools/adb.exe' reverse tcp:8765 tcp:8765
& '.tools/sdk/platform-tools/adb.exe' install -r 'app/build/outputs/apk/pilot/app-pilot.apk'
```

先按下文编译 `assemblePilot` 生成 ARM64 内测包。x86 模拟器或其他支持的架构使用 `app/build/outputs/apk/debug/app-debug.apk`。手机登录页“服务连接”使用 `http://127.0.0.1:8765`。在安卓模拟器中也可以使用端口转发，或把地址改为 `http://10.0.2.2:8765`。在页面内自行注册账号。

使用另一台手机时，同样设置该手机的端口转发。真实远程部署应配置 HTTPS 域名。开发调试包允许 HTTP，发布构建只接受 HTTPS。

暂时没有后端时，可点击“先离线体验 OCR 与页面”。离线内容保留在独立的本机体验空间，登录真实账号后不会自动上传这些内容。

## 编译

本次任务准备的便携工具保存在 `.tools`，没有修改系统 JDK 与全局 PATH。工程也可以直接用 Android Studio 打开，使用兼容的 JDK 17 与 SDK 35。

```powershell
# 当前机器已经准备工具时
powershell -ExecutionPolicy Bypass -File scripts/build.ps1

# 在全新 Windows 环境准备便携工具
python scripts/bootstrap.py
powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -SetupSdk
```

`-SetupSdk` 会安装官方 SDK 软件包并接受相应 SDK 许可。下载按官方 SHA-256 校验。`.tools` 不需要提交给队友或版本仓库。

默认任务包括构建调试 APK、本地单元测试和 Android Lint。生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Gradle Wrapper 也提供常规 `gradlew.bat assembleDebug` 入口，需要本机配置正确的 JDK 与 SDK。

针对 ARM64 手机的轻量内测构建开启代码与资源压缩，可运行 `& './scripts/build.ps1' -Tasks @('assemblePilot')`。输出位于 `app/build/outputs/apk/pilot/app-pilot.apk`，签名为开发调试签名，可用于内部安装。`pilot` 与 `debug` 允许 HTTP 联调，正式 `release` 保持 HTTPS 要求。最终交付包在 `dist` 中。

## 联调演示顺序

1. 启动后端，用户 A 与 B 分别注册账号。
2. A 在“我的”中创建测试组并生成邀请码，B 用邀请码加入。
3. A 导入截图，校对文字和发生时间，选择允许上传后保存。
4. 确认线索状态变为“已同步”，在首页生成联调建议。
5. 打开依据核对内容，采纳或忽略，刷新后反馈仍然保留。
6. B 的线索列表保持独立。A 从线索卡片选择明确共享，B 才能在组详情看到共享文字。
7. A 撤回共享，刷新 B 的组详情确认移除。
8. 关闭网络后保存允许上传的线索，恢复网络或点击同步，确认没有重复记录。

当前“采纳”记录反馈，不会自动修改外部日历、发送聊天消息或进行支付。

## 项目入口

给队友交接时可以发送 `dist/weiguang-source-0.1.0.zip` 和 ARM64 内测 APK。源码包包含代码、协议、测试与截图，排除了便携工具、构建缓存、本机数据库和本机路径配置；队友按上面的全新环境步骤或 Android Studio 方式准备环境。

| 文件或目录 | 用途 |
|---|---|
| `app/src/main/java/cn/weiguang/app/ui` | 主题、页面、卡片、编辑弹层 |
| `app/src/main/java/cn/weiguang/app/data` | Room、会话、网络、同步与数据协议 |
| `app/src/main/java/cn/weiguang/app/capture` | OCR、日历、定位与通知测试 |
| `backend/main.py` | 可运行联调后端与规则提供者 |
| `docs/TEAM_HANDOFF.md` | 队友分工、接口行为、验收与交付要求 |
| `docs/UI_AND_ARCHITECTURE.md` | 页面规格、视觉规范与代码扩展方式 |
| `docs/openapi.json` | 从实际后端导出的 API 描述 |
| `docs/decision.schema.json` | 决策结果结构与允许的动作 |
| `docs/TEST_REPORT.md` | 实测结果与尚需真机确认的项目 |

运行后端测试使用 `& '.venv/Scripts/python.exe' -m unittest backend.test_api -v`。可复用的 Android 设备测试保存在 `app/src/androidTest`。先启动后端，再运行 `& './scripts/test-device.ps1' -Serial 'emulator-5554'`，它会构建并安装测试包、设置端口转发、执行测试并拉取截图。替换 Serial 可以指定其他测试设备。该测试会创建随机名称的专用测试账号，覆盖真实页面、端侧中文 OCR 与本地账户隔离。

## 发布前补齐

后端当前使用 SQLite 和单进程内存限流，适合小规模内部联调。公开部署前需由云端同学完成 HTTPS、数据库迁移、集中限流、运维备份、密码找回与上线账户管理。正式上架需配置团队签名并核对届时渠道要求。

OPPO 真机的权限行为、耗电、内存和穿戴显示需要在具体型号上验收。可以先安装本版运行手机功能，穿戴接入独立推进。
