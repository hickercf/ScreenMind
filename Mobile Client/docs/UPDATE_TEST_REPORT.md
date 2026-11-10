# 应用内更新验证 · 2026-11-10

范围：手机 v0.3.0 / versionCode 3、网关更新接口、发布脚本与部署配置。Cloud Agent 业务代码本轮未修改，沿用上一轮验证；没有新增真实模型调用或用户数据测试。

| 验证 | 实际结果 | 本地证据 |
|---|---|---|
| 网关旧接口、Agent 桥接、更新接口 | 26 项通过，18.014 秒；mock 模型 | `artifacts/update-backend-tests.txt` |
| Android 单元测试 | ContractTest 3 项 + AppReleaseTest 3 项，0 失败 | `app/build/test-results/testDebugUnitTest/` |
| Android 构建 | assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 成功，3 分 24 秒 | `.tools/build-updates.log` |
| Android Lint | 0 错误，17 项警告；包括既有依赖/风格/ABI 提示及新增 Uri.toUri 风格提示 | `app/build/reports/lint-results-debug.html` |
| APK 真实签名、版本读取 | apksigner 验签通过，aapt2 读取包名 cn.weiguang.app、版本 3 / 0.3.0、最低 API 29 | `backend/releases/update-debug.json` |
| 真正 HTTP 下载 | 旧客户端获得 v3，当前版本收到 204；完整 APK 下载的大小及 SHA-256 匹配，首次下载页可访问 | `artifacts/update-http-report.json` |
| 发布脚本保护 | 实际 APK 测试：拒绝降级、同版本号不同摘要、debug 包误发 pilot 渠道 | 同上 |
| Compose 配置 | 以占位参数执行 `docker compose config --quiet` 成功 | `deploy/compose.yaml` |

实际 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，大小 **107,904,810 字节**，SHA-256：

```text
3af162de9140d0a08fbbe434b52f68628f4db8e471cd4360878c6530aa64845c
```

签名证书 SHA-256：`f3edfb2a919472bdc28966682dc476f0cf6b8bb53aad07093c248078ca8c3358`。这是当前开发内测证书的公开指纹，不含私钥。后续兼容覆盖安装必须保持签名一致。

发布验证修正了 Windows 默认 GBK 解码 aapt2 输出失败，以及 SDK 35 输出 `minSdkVersion` 的读取适配。脚本已用真实 APK 重新执行成功。

## 尚未完成

当前 `adb devices` 无设备。未声称以下项目已通过：Android 安装来源授权、打开系统安装器、覆盖升级后 Room 数据和会话保留、断网/取消下载、手机端拒绝不同签名 APK。设备测试包编译成功不等于已执行设备测试。

服务器尚未租用，无真实域名。Compose 仅验证配置解析；本机 Docker 引擎未启动，尚未执行镜像构建与容器运行验收。也未进行 HTTPS、公网可达性、带宽和并发测试。4 核 / 8 GB 为起步建议，不是性能测量结论。

## 下一步真机验收

1. 在手机安装 v0.3，配置部署后的 HTTPS 地址，登录并创建一条本地记录和一个云端事项。
2. 使用相同证书构建更高 versionCode 的 APK，发布到同一渠道；确认前台自动发现和手动检查均可用。
3. 下载后完成安装授权与系统安装确认；重开 APP，检查版本号、账号、原记录与事项。
4. 用测试清单检查下载中断/取消可重试；构造摘要不符或不同签名的测试包，确认不能进入安装。
5. 版本清单或服务器暂不可用时，原有 OCR、登录、线索和 Agent 页面继续正常工作。
