# 悬浮窗与后台 OCR 验收 · 2026-11-17

版本：微光0.4.1 / versionCode5，debug测试包。设备为Android官方模拟器，Android15 / API35 / x86_64，720×1280、2个虚拟CPU、2GB内存。测试材料与账号均为合成数据；使用独立本机网关8876和mock模型，不连接真实聊天账号或公网用户数据库。

## 结果

| 检查 | 最终结果 |
|---|---|
| 主APP、设备测试包、独立聊天测试页构建 | 通过 |
| 主APP单元测试 | 13项通过，其中7项为新增的采集防抖、限频、忙时保留请求与取消测试 |
| 共享OCR核心单元测试 | 15项已有通过结果复用，Gradle判定源代码未变化 |
| Android Lint | 0错误、17项警告 |
| 无障碍服务与悬浮窗设备用例 | 1项通过，含下方12个检查点，设备侧33.136秒 |
| 原有设备回归 | 6项全部通过，40.089秒：页面、OCR基线、账号隔离、组流程、数据库迁移、事项确认 |
| 真实断网与三端补传确认 | 1项通过，设备侧15.605秒，主机控制和截图收集18.23秒 |

这些耗时是单次流程观测，不代表OCR准确率、生产延迟、并发容量或耗电水平。

## 服务级用例实际验证的12个检查点

1. 独立的`capturefixture`应用显示合成聊天画布；系统窗口事件触发微光无障碍服务，使用真实系统截屏和ONNX PP-OCR识别“A302”等文字。服务用例不直接调用事件处理方法，也不向OCR预填识别结果。
2. 实际悬浮窗可以收起、展开，显示绑定事项与同步情况。
3. 重复内容触发OCR后，去重不新增材料。
4. 停留在同一窗口，新增“小林今天不来了”后，由内容变化事件触发采集。
5. 点击悬浮窗暂停，后续滚动不发起新的截屏请求，也不新增本地材料。
6. 点击继续，采集当前可见的“地点改到B201”；旧授权即使对应相同事项，也不能保存旧帧。
7. 滚动事件触发系统截屏。
8. 已识别的非聊天测试窗口不继续采集，也不向事项写入其内容。
9. 暂停期间切到非聊天窗口，再继续时仍保持过滤。
10. 设置`FLAG_SECURE`的测试窗口拒绝截图；重复事件也不产生新的成功截图或材料。
11. 3条新增材料生成3个不同的Agent任务；提交材料后正式事项版本仍为v0，等待用户确认。
12. 点击悬浮窗结束，撤销采集事项授权并移除悬浮窗。

本轮服务用例记录14次截屏请求、12次成功回调、3条新增材料、3次去重。请求、回调与入库是不同指标：窗口或授权变化时，已经发出的回调仍可能返回，画面不会因此自动入库。暂停边界使用请求计数检查，避免把迟到回调误认为新的采集请求。

## 原有三端流程复核

`IntegratedCaptureTest`再次使用真实OCR识别合成聊天图。主机实际移除ADB端口转发，验证网络失败后Room仍保留结构化证据和固定任务请求ID；恢复转发后补传并创建唯一任务。用户通过Compose页面确认候选，正式状态从v0变为v1。退出自动记录后授权为空。

`DeviceFlowTest`和`AgentFlowTest`六项回归覆盖原有页面与主题、中文OCR、账号隔离、注册/组/建议操作、Room迁移和用户明确确认事项。没有为通过测试修改既有业务断言。

## 测试中修正的问题

- 切页时旧窗口的迟到事件可能覆盖新页请求：截图前重新关联真实活动窗口，对根节点尚未稳定的请求作有限延迟重试。
- 暂停期间未更新页面类型，以及通用布局事件清除非聊天判断：暂停时维护页面分类，已识别的非聊天窗口不会被通用控件事件重新归类。
- 暂停/继续和保存之间的时序：每次控制生成新授权标识，在同一保存边界检查账号、事项、暂停状态和标识；取消服务时不会继续显示旧悬浮窗。
- 测试启动顺序、模拟器首次全屏提示、异步回调统计和合成账号工作区配额分别造成过失败。已调整测试启动、保留系统栏、区分请求与回调，并在新的独立测试库上完成最终回归。原来的合成数据和部分失败报告保留，没有清空公网数据，也没有关闭网关配额限制。

## 产物与证据

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 版本：0.4.1 / code5；大小182,059,100字节，约173.6MiB。
- SHA256：`187f1a1b9f193cd7bd193827976469064876250e8f563533149e48e935b4eb5e`。
- 构建、单元测试和Lint汇总：`artifacts/capture-service/build-report.json`。
- 服务报告及输出：`artifacts/capture-service/report.json`、`device-output.txt`。
- 窗口事件诊断：`artifacts/capture-service/accessibility-events.json`，仅窗口编号、事件类型和类名，无聊天正文。
- 悬浮窗截图：`artifacts/capture-service/overlay-running.png`、`overlay-paused.png`。
- 六项回归输出：`artifacts/capture-service/regression-output.txt`。
- 断网报告与确认截图：`artifacts/capture-service/outbox/device-report.json`、`outbox/screenshots/integrated-confirmed.png`。
- 最终测试库：`artifacts/capture-service/final-run/`。原先多轮测试库保留在上级目录。
- 最终构建日志：`.tools/build-capture-service-final.log`。

## 复现入口

```powershell
./scripts/build.ps1 -Tasks @('--offline', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':capturefixture:assembleDebug', ':app:testDebugUnitTest', ':chronocore:testDebugUnitTest', ':app:lintDebug')
./.venv/Scripts/python.exe scripts/test-capture-service.py --serial emulator-5560
./.venv/Scripts/python.exe scripts/test-integration.py --serial emulator-5560 --output-dir artifacts/capture-service/outbox
```

离线构建要求依赖已经缓存；首次构建可移除`--offline`。设备脚本要求先启动独立mock网关8876。服务测试临时启用专用模拟器的无障碍服务，结束后还原设置；正式使用需用户手动授权。完整行为和公共API依据见[采集说明](CAPTURE_OVERLAY.md)。

## 验证范围

- 本轮完成本地实现和模拟器验证，没有推送GitHub或发布公网自动更新渠道。
- 模型生成使用mock；真实的是系统事件、截屏、OCR、持久化、网络故障、同步和用户确认流程，不是本轮真实模型的语义准确率实验。
- 真实微信/QQ、不同ROM、群聊/深色主题/大字体、长期后台存活及耗电尚待真机验收。页面类过滤是启发式规则，不能保证识别所有第三方应用页面。
- Android11–13隐藏悬浮窗后截图的兼容路径尚未实机验收；本轮验证Android15的窗口截图路径。
- 当前自动采集面向用户正在查看的微信/QQ窗口；没有后台读取其他应用或逐帧录屏。原始截图不进入上传协议。
