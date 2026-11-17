# 悬浮窗与后台聊天 OCR

开发版本：0.4.1 / versionCode 5。自动截图支持 Android 11 / API30 及以上。

## 使用流程

1. 登录微光，进入「云端事项」，创建或选择目标事项。
2. 点击「允许自动记录到此事项」，在系统无障碍设置中手动开启「微光聊天记录」。
3. 屏幕上出现悬浮窗，显示绑定事项、采集状态、待同步数量及已提交 Agent 的材料数量。
4. 打开微信或 QQ 聊天页。窗口切换、内容变化和滚动事件触发截屏，本地 OCR、结构化、跨屏去重后进入 Room 同步队列。
5. 悬浮窗可拖动、收起、暂停、继续和结束。暂停不再截屏，已保存且已授权的材料仍可同步；结束撤销目标事项授权并移除悬浮窗。

悬浮窗使用 `TYPE_ACCESSIBILITY_OVERLAY`，由已授权的无障碍服务管理，不增加另一个普通悬浮窗权限入口。用户关闭系统无障碍服务后，采集与悬浮窗一起停止。

## 采集边界

- 只处理当前前台的微信、QQ 窗口；不会读取退到后台的应用内容。测试构建另外允许独立的合成聊天测试包，release/pilot 不包含该授权。
- 监听 `TYPE_WINDOW_STATE_CHANGED`、`TYPE_WINDOW_CONTENT_CHANGED`、`TYPE_VIEW_SCROLLED`，事件后等待350ms，持续变化最多合并1200ms。单帧处理期间保留最新待采集请求。
- 截屏间隔至少1500ms，每分钟最多20次；额度不足时推迟最新请求，不因高频事件开并行 OCR，也不空闲轮询。
- 截图前检查实际活动窗口、包名、锁屏和输入法窗口；页面和账号变化时放弃旧帧。网页、相册等已有窗口类过滤仍保留，无法保证覆盖所有第三方页面。
- 切页时忽略旧窗口的迟到请求，重新关联实际前台窗口；窗口根节点尚未稳定时最多延迟重试两次。暂停时只维护页面类型，恢复时不会直接把期间打开的已识别非聊天页当作聊天采集。
- 已识别的非聊天窗口不会被后续 `FrameLayout`、`TextView` 等通用控件事件重新归类；仅应用自身明确的页面切换可解除该窗口的过滤，缓存最多保留32个窗口标识。
- Android14 / API34 及以上使用 `takeScreenshotOfWindow`，避免悬浮窗文字进入 OCR。Android11–13 短暂隐藏悬浮窗后使用显示器截图；该兼容路径须另外完成设备验收。
- 原始截图只在内存处理，网络接口只上传授权的结构化文字，不上传图片。
- 每次启用、暂停或继续生成新的授权标识，保存时在数据库写入前校验账号、事项、暂停状态及标识。旧帧不能因为重新启用相同事项而获得新的授权。
- 暂停后不再发起截屏请求；暂停前已发出的系统回调仍可能返回，但旧授权的画面不会继续保存。设备测试分别记录请求次数与成功回调次数，避免将迟到回调误认为新的采集请求。
- 正式事项状态仍须用户确认候选后更新。自动采集和上传不等于自动接受模型建议。

## 验证入口

```powershell
./scripts/build.ps1 -Tasks @(':app:assembleDebug', ':app:assembleDebugAndroidTest', ':capturefixture:assembleDebug', ':app:testDebugUnitTest', ':chronocore:testDebugUnitTest', ':app:lintDebug')
./.venv/Scripts/python.exe scripts/test-capture-service.py --serial emulator-5560
```

设备测试需要独立 mock 网关在本机8876运行。`test-capture-service.py` 仅允许模拟器序列号，临时启用该模拟器的采集服务，结束后还原系统设置与端口转发。`capturefixture` 是无网络权限的独立合成聊天应用，不作为微光依赖打包；通过系统事件和真实截屏调用服务，测试不直接调用服务的事件处理方法。

测试数据和截图保存在 `artifacts/capture-service/`。合成应用事件验证不能替代真实微信/QQ、不同ROM及耗电测试。实际执行结果另见 `CAPTURE_OVERLAY_TEST_REPORT.md`。

## 官方 API 依据

- [AccessibilityService：takeScreenshot / takeScreenshotOfWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [WindowManager.LayoutParams：TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)
- [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)
