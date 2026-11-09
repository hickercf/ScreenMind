# 微光 0.1.0 · 开发验证记录

验证日期：2026-11-08。本记录区分实际运行验证与尚未测试的设备能力。

## 环境与产物

- Windows 本机构建，便携 JDK 17、Gradle 8.11.1、Android SDK 35。
- 应用最低 Android 10（API 29），目标 API 35。
- 设备自动化环境：Android 10 / API 29 官方 x86_64 模拟器，画面 640 × 1280，密度 240 dpi。
- 后端：工程 `.venv`，精确依赖见 `backend/requirements.lock.txt`，实际 HTTP 服务监听本机 8765。
- `dist/weiguang-0.1.0-arm64.apk`：面向 ARM64 手机的内测包，约 17.3 MB，启用代码和资源压缩、开发签名。
- `dist/weiguang-0.1.0-universal-debug.apk`：供模拟器和多架构调试使用。运行验证使用此构建对应的 debug APK。
- 精确文件大小和 SHA-256 见 `dist/SHA256SUMS.txt`。APK 签名通过 Android `apksigner` 校验。

## 已完成验证

| 项目 | 结果与实际覆盖 |
|---|---|
| 内测与调试包构建 | `assemblePilot`、`assembleDebug` 成功 |
| 后端测试 | 13 项通过：真实密码、重复账号、会话轮换与撤销、私有数据隔离、明确共享与撤回、组长权限、过期邀请、并发加入、事件版本与幂等、建议反馈与失效、删除、输入校验和限流 |
| Android 本地单元测试 | 3 项通过：标准化字段、输入校验、采集时间与发生时间分离 |
| Android 设备测试 | 4 项通过：离线录入及主题、真实端侧中文 OCR、Room 账户隔离、真实页面云端流程 |
| 页面端到端流程 | 注册新账号 → 创建测试组 → 生成邀请码 → 录入并上传线索 → 生成规则建议 → 采纳反馈 → 设备页面 → 深色主题 |
| Android Lint | 0 错误、15 条提示：依赖版本 10 条、非 ChromeOS 目标 1 条、同步保存会话 1 条、KTX 写法 3 条 |
| 视觉复核 | 真实运行截图已存入 `docs/previews`；修正导航选中色、深色系统图标和建组输入法焦点 |
| 电脑一键启动 | 实际运行 `scripts/start-desktop-test.ps1`，成功启动后端及可见模拟器、配置端口转发、安装并打开 App；窗口标题为 `Android Emulator - WeiguangTest:5556` |

中文 OCR 测试在设备中生成含“明天 18:30 在 A302 讨论项目”的图片，实际调用内置中文识别器，并校验识别结果包含 A302 和中文关键词。没有以固定返回文本代替 OCR。

云端流程测试使用真实运行的本地 HTTP 服务和随机测试账号。建议由明确标注的 `rule_demo` 生成，不代表已经接入 AI 模型。组内加入、共享与撤回等细节由后端测试覆盖，单台设备页面测试只覆盖建组和邀请码生成。

## 复现

先按 README 启动联调后端和安卓设备，再在工程根目录执行：

```powershell
& '.venv/Scripts/python.exe' -m unittest backend.test_api -v
& './scripts/build.ps1' -Tasks @('assemblePilot','assembleDebug','testDebugUnitTest','lintDebug')
& './scripts/test-device.ps1' -Serial 'emulator-5554'
```

设备测试原始输出见 `docs/device-test-output.txt`。本地单元测试与 Lint 报告分别在 `app/build/reports/tests/testDebugUnitTest` 和 `app/build/reports/lint-results-debug.html`。

页面截图：`login.png`、`home.png`、`clues.png`、`groups.png`、`devices.png`、`profile-dark.png`。截图中账号、组名和线索均为测试数据。

## 仍需真机与业务验收

1. ARM64 压缩内测包已构建及校验签名，但当前机器只有 x86_64 模拟器，未在 OPPO 真机运行。需在团队指定的 OPPO 型号、ColorOS 版本上安装验收。
2. 相册/外部分享图片入口、真实日历权限和数据、定位服务、Android 13 及以上通知权限，需要具体真机逐项确认。当前中文 OCR 自动化验证的是识别器本身。
3. 持久同步队列已实现；本次自动化未覆盖长时间断网、系统杀进程后恢复和厂商后台限制。后端请求幂等和版本冲突已测，完整恢复流程需按 README 的联调步骤人工验收。
4. 手表通知转发是否送达、健康字段、设备授权和腕上按钮回传尚未测试，需要设备同学交付 SDK 和真实设备。腕上页面是设计预览。
5. 连续屏幕采集和 QQ/微信通知入口未实现；本版使用主动选择/分享的图片进行端侧 OCR。
6. 真实 AI 场景、模型质量、延迟与费用尚未定义。当前仅验证输入、输出和反馈通路。
7. 没有进行耗电、内存、长时间稳定性、大字体无障碍或公开部署负载评估。当前后端适合内部联调，正式发布要求见 README。

不要将“构建通过”和“模拟器通过”解释为所有手机与穿戴设备已适配。
