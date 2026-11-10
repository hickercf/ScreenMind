# 应用内更新 · v0.3

## 用户体验

debug / pilot 内测版新增右上角“更新”入口，登录前也可访问。应用启动或回到前台时检查版本，同一服务地址每 4 小时最多自动检查一次；也可手动检查。

发现新版本后展示版本号、大小、更新说明。用户发起下载，APP 显示进度并检查大小、SHA-256、包名、实际版本和签名。通过后打开系统安装页。首次可能需要允许本应用安装软件，返回后再次点击安装。正常消费设备的系统安装确认仍由用户完成。

下载期间需保持应用开启；支持取消及重新下载，暂不支持断点续传或后台下载保障。已经下载完整的同一版本可在检查更新时重新校验后继续安装。

v0.2 及更早版本没有更新入口，需要先安装一次 v0.3。后续仍会下载 APK，只是不再需要开发者通过聊天、数据线逐台传包。云端 Agent 的逻辑更新无需更新 APP。

这是内部 APK 分发机制，普通 release 构建关闭此入口与安装权限。若将来进入 Google Play，需要另外接入平台更新渠道，不能直接沿用该自更新方式。参考 [Android 分发说明](https://developer.android.com/studio/publish)、[Android 安装来源授权说明](https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html?m=1) 及 [Google Play 安装权限政策](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en)。

## 版本与下载接口

| 接口 | 行为 |
|---|---|
| `GET /v1/app/update?channel=debug&version_code=2` | 返回适用的新版本清单；无新版为 204，不缓存 |
| `GET /v1/app/releases/{sha256}.apk` | 下载按内容命名的不可变安装包 |
| `GET /download` | 手机浏览器首次下载页 |

版本与安装包是公开的内部测试分发资源，不需要账号令牌；不会包含模型密钥。APK 不保存在 Git 源码仓库，服务读取 `WEIGUANG_RELEASE_DIR`，默认 `backend/releases`。

```json
{
  "schema_version": 1,
  "package_name": "cn.weiguang.app",
  "channel": "debug",
  "version_code": 3,
  "version_name": "0.3.0",
  "min_sdk": 29,
  "sha256": "由发布脚本计算的64位小写十六进制值",
  "size_bytes": 108000000,
  "notes": "更新说明",
  "download_url": ""
}
```

上面仅为字段示意，大小和摘要不能用于真实发布。`download_url` 为空表示从同一服务下载；对象存储填写可直接下载的 HTTPS 地址，不能是跳转页面、临时网页或带账号密码的 URL。客户端不跟随 HTTP 重定向，也不携带登录凭据。

## 开发者发布一次，手机自行获取

1. 修改 `app/build.gradle.kts`，提高 `versionCode` 和 `versionName`。
2. 保持应用 ID 和签名密钥不变；debug 与 pilot 各自使用自己的版本清单。当前二者使用同一台开发机的 debug keystore，需私下备份并在换电脑/CI 时保持一致，不能提交私钥。正式团队分发后再迁移到受控签名管理。
3. 构建并准备发布文件，在 `Mobile Client` 执行：

```powershell
& ./scripts/build.ps1 -Tasks @('assembleDebug','testDebugUnitTest','lintDebug')
& ./.venv/Scripts/python.exe ./scripts/prepare-update.py ./app/build/outputs/apk/debug/app-debug.apk --channel debug --notes '本次更新说明'
```

脚本调用 Android SDK 的 aapt2 读取 APK 实际版本，调用 apksigner 验证签名，计算摘要；先生成完整 `SHA256.apk`，最后原子替换 `update-debug.json`。同一版本号不能替换成不同 APK，降级会被拒绝。它不会自动签名或上传。

4. 将生成的 **APK 先上传**到服务器 `Mobile Client/backend/releases`，完成后将清单传为临时文件，再在服务器同目录重命名为 `update-debug.json`。不要直接覆盖正在被读取的 JSON；避免用户下载未传完的包。服务器目录与文件应允许容器用户读取。
5. 手机打开 APP 等待自动检查，或“更新 → 检查更新 → 下载新版 → 安装更新”。

构建 pilot 时使用 `assemblePilot` 与 `--channel pilot`，发布 ARM64 APK。不同渠道不自动切换。

## 对象存储分发

发布脚本支持：

```powershell
& ./.venv/Scripts/python.exe ./scripts/prepare-update.py ./app/build/outputs/apk/debug/app-debug.apk --channel debug --notes '本次更新说明' --download-base-url https://downloads.example.com/weiguang
```

脚本仍在本地生成文件，清单的下载地址指向 `HTTPS前缀/SHA256.apk`。先上传 APK 并核对远程下载的摘要，再把清单发布到网关。服务端不验证外部文件是否已上传，客户端仍严格校验。避免短期过期链接影响后续安装。

## 验证范围

服务接口测试覆盖无新版、渠道隔离、版本比较、损坏清单、不完整文件、HTTPS 对象存储地址、下载内容与页面转义。Android 单元测试覆盖版本清单约束与下载地址规则；构建和 Lint 验证见 [更新验证记录](UPDATE_TEST_REPORT.md)。

无连接的 Android 真机，安装来源授权、系统覆盖安装、旧版本本地数据保留、断网取消恢复及签名不匹配提示仍需在实际手机上验收。实现了这些控制不等于已完成真机验证。
