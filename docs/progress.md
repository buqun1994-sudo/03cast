# 项目进度

## 2026-09-02 真实 Staging 退款 / 试用到期链路复核（已完成）

1. 已纠正验证基线：此前安装的 Debug fixture 不能代表测试环境；本轮安装真实 Staging Debug（`https://api-staging.9studio.fun`、`com.ninepointnine.desktopcast`、Staging 证书摘要 `98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a`）。设备安装 APK 与桌面 staging APK SHA-256 均为 `4be0c018ac45abcfe6c33ad6c7a531e2c3d50a2776d298187824067693e3e37c`。
2. 车机 `S56_HQX / Android 9 / 1920x1080` 命中测试环境已有的退款撤权与试用到期记录，未重新发放试用；在线复核完成后等待投屏页显示“权益已撤销 / 请重新获取Pro以继续”，只保留“获取Pro”入口。启动复核尚未完成时短暂显示“正在确认投屏权益”，属于异步检查中的正常临时态。
3. 权益中心显示“权益已撤销，需重新获取Pro”；点击后重新取得 Staging 服务端报价（`¥0.02`）并进入订单详情，创建订单后真实二维码正常显示。使用车机全局返回回到权益中心后，等待 `7 s` 未被晚到查询或支付轮询重新带回二维码页。
4. 复核期间 `CastService` 和 `7000 / 8200 / 1900` 监听保持，说明撤权只释放媒体输出，不关闭接收运行时；测试订单在下一次撤权生命周期复核时按客户端逻辑清理。
5. 已重新生成并覆盖桌面测试 / 正式目录产物：Staging APK `27103199` 字节、SHA-256 `4be0c018ac45abcfe6c33ad6c7a531e2c3d50a2776d298187824067693e3e37c`；Staging ZIP `10201330` 字节、SHA-256 `0f89e71061850eedcae4a317e5be1287b139aa31f535bd215ce22c19f1603f31`；Production APK `11665741` 字节、SHA-256 `3ff1c198366b7fe6fcf9f693651217bf687df93b60a4f2f313bb692b22cf3d67`；Production ZIP `4807101` 字节、SHA-256 `e9b29eed83736169fb1c671582c5031a01310ecbe49f1a4dc502a0a9cbd19e23`。两个 ZIP 均只有一个同名 APK、设置 UTF-8 文件名标志，且解压字节与 APK 完全一致；APK 包名、版本、单 signer、APK v2 和环境 API 地址均已核对。
6. 已验证：全量 Debug JVM 单测、Debug lint、Staging `assembleDebug`、Production `assembleRelease`、项目就绪检查、skills 检查、安装脚本语法和 `git diff --check` 通过。模板快检因当前仓库是已初始化具体项目而不适用；03 APP 登记库直检仍被共享登记中旧的 production 版本 / HEAD / clean 快照阻断，未修改 Cloud 登记库。

## 2026-09-02 03cast 商业页面导航与生命周期复核解耦（已完成）

1. 已定位：`MainActivity.openSettings()` 把同一 Activity 内的设置分区切换误当成权益生命周期，进入订单页和二维码返回都会启动新的查询；查询完成后状态机又无条件按待支付快照重建页面，导致订单页回权益首页、二维码返回后重入二维码。
2. 已施工：标准窗口的在线权益复核收敛到 `MainActivity.onStart`；`FullscreenActivity` 不重复承担 UI 复核；设置分区、权益首页、订单页和二维码返回不再触发 `reloadEntitlement`，支付会话持久化与 `CastService` 生命周期复核保持不变。
3. 已施工：`CommercialUiState` 增加页面 owner，状态机在同一查询尚未完成时保持用户选择；支付创建后的二维码由操作 owner 持有，避免空待支付快照清掉刚创建的会话；新生命周期清除 owner 并可恢复权威待支付二维码，`Pro` / 撤权 / 设备不匹配等权威结果仍强制覆盖页面。
4. 已补充：`CommercialStateMachineTest` 覆盖晚到待支付快照、晚到空待支付快照、新生命周期恢复二维码和权威 Pro 覆盖订单页；相关安全规则、施工方案和验证矩阵已同步。
5. 已验证：全量 Debug JVM 单测 `164` 条通过，Debug lint、Debug assemble、项目就绪检查、技能检查和 `git diff --check` 通过；模板快检因本仓库是已初始化具体项目而按预期不适用，多语言快检因项目没有 `src/locales` 而不适用。
6. 已构建并校验：staging Debug `0.1.0` / versionCode `1`，APK `27101947` 字节、SHA-256 `cd6b5157ca8a8fb424f48e42b913437962728ba5037f64f93a9939dc7700830e`；production Release `1.0.2-icar03` / versionCode `3`，APK `11664485` 字节、SHA-256 `2f10fe848f3ce928d54750968fd408f9889c3adf814d383bf8a9cb1416f8a5c9`。两包均为 `com.ninepointnine.desktopcast`、单 signer、APK v2，证书摘要分别与 staging / production 登记值一致。
7. 桌面产物：staging APK / ZIP 已覆盖原文件；production 生成 `03投屏-v1.0.2-icar03.apk` 与 `03投屏-v1.0.2-icar03.zip`，旧 `1.0.1` 文件保留以避免版本元数据与文件名不一致。两个 ZIP 均只有一个同名 APK 条目、UTF-8 文件名标志、解压字节与 APK 一致；staging ZIP `10158895` 字节 / SHA-256 `23b42426ba2e41d09b7c53be1defd6f1de4dbc13197a7e674d86911d19fa3405`，production ZIP `4787608` 字节 / SHA-256 `18ff916c66c450fb41e173bead67a3884fe495abb1e494f9b4e3732e0d262771`。
8. 目标车机订单页 / 二维码返回仍需按验证矩阵手测，未宣称交互验收完成。

## 2026-09-02 staging 与 production 包重新构建并覆盖（完成）

1. 已从已推送提交 `b91ca7c` 重新构建 staging Debug（`0.1.0` / versionCode `1`）和 production Release（`1.0.1-icar03` / versionCode `2`）；Release 的 R8、资源收缩和签名任务均成功。
2. staging APK 已核对为 `com.ninepointnine.desktopcast`、单 signer、APK Signature Scheme v2，证书摘要为登记的 `98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a`；文件大小 `27101947` 字节，SHA-256 为 `d0acc7c04d1394a5f5d7a8e5d5e35cd07e88790460c27082185c05e7b3a10841`。
3. production APK 已核对为 `com.ninepointnine.desktopcast`、`1.0.1-icar03` / versionCode `2`、`debuggable=false`、单 signer、APK Signature Scheme v2，证书摘要为登记的 `14e4a7cdf1481afdb871487aa830bb0dc28910c0ba681693f11f9f1dd2fd4423`；文件大小 `11664485` 字节，SHA-256 为 `317663da2cd368bbec2024e893706de51db668164aa9af426c124774cba8259b`。
4. 两个 APK 均已重新生成单 APK ZIP，并覆盖桌面旧文件：staging ZIP `03投屏-staging-v0.1.0-icar03.zip` 大小 `10200427` 字节、SHA-256 为 `88846c72466dc8e81943d55d9f4606eddaa41faaef6ffc9d89ea62ea2add9776`；production ZIP `03投屏-v1.0.1-icar03.zip` 大小 `4805405` 字节、SHA-256 为 `2bff47693021cc4305d98a5075b31cab4ee2d811abfbc2bea2a4f53926f8d5c0`。两个 ZIP 均显式设置 UTF-8 文件名标志、只有一个同名 APK 条目，解压回读与外部 APK 字节一致。
5. 本轮已完成客户端提交并推送 `origin/main`；未安装 APK、未部署测试环境、未上线 production，也未修改 Cloud 登记库或发布配置。

## 2026-09-02 03cast 永久 PRO 与生命周期在线复核移植

1. 已完成：将 03 歌词已验收的设备商业生命周期主链移植到 `03cast`，产品身份保持 `03cast / 03cast_pro_device_cny / com.ninepointnine.desktopcast / icar03`，未修改 03 歌词或 cloud。
2. 已完成：客户端启动、新的 `CastService` 生命周期和用户重试先验签本地许可证，再以 `purpose=check` challenge 调用 `POST /v1/products/03cast/device-access/license/check`；设置页与订单 / 二维码页在同一主窗口内，不单独触发复核；`active` 不签发新许可证、不生成 `licenseId`、不改写本地许可证 bytes。
3. 已完成：购买 / 恢复签发的 PRO 许可证使用 `validity=permanent`，三个时间字段均为 `null`；试用固定七天，单张试用许可证最长 24 小时，租约到期时沿用当前设备密钥取得下一张租约。
4. 已完成：云端 `revoked` 清除本地许可证、device token、支付与待复核记录，并仅释放投屏媒体输出；接收 runtime、DLNA / AirPlay 广播、窗口和行车安全主链保持原有边界。一般网络失败保留仍有效凭证并记录待复核；云端明确 `device_key_mismatch` 后，恢复成功并完成新许可证验签持久化前不会用旧凭证放行。
5. 已补充：Debug fixture 的 `license/check` 路由、请求计数和永久 / 短租约许可证；商业网关、协调器、运行时守卫、服务适配器及直接相关 JVM 用例已同步，并覆盖密钥不匹配恢复失败的 fail-closed 边界。
6. 已验证：本轮最终项目检查、Debug 单测、Debug 构建、Release Kotlin 编译和 `lintDebug` 均通过；真实 S56_HQX 车机上的生命周期复核、退款撤权、端口释放和 DLNA / AirPlay 商业门禁仍按验证矩阵由用户手测，未默认安装或运行 smoke。

## 2026-08-24 Release 版本规则

1. Release 版本真值固定在根目录 `release-version.properties`，当前 `versionName=1.0.1-icar03`；Debug / Staging 继续使用原有 `0.1.0` 基线。
2. 未指定版本时由 `scripts/bump-release-version.mjs` 递增 patch 并同步递增 `versionCode`；明确指定版本时使用传入值。构建过程不会自动改写版本文件。

## 2026-08-17 项目初始化

1. 目标：将新项目模板初始化为 03投屏。
2. 项目类型：desktop-client。
3. 一句话定位：Android 9 iCAR 车机上以最简界面稳定接收 DLNA 与 AirPlay 的独立投屏器
4. 已完成：写入项目身份、项目长期总纲、产品需求基线和验证矩阵。
5. 已确认：目标用户、核心路径、单会话状态机、技术栈、运行级入口和 V1 不做范围。
6. 当前边界：独立 APK、标准右侧浮窗、DLNA 与 AirPlay 同时在线；不进入 `03桌面` 常驻主链。
7. 验证：待执行 `node scripts/check-project-ready.mjs`。
8. 未完成：Android 工程底座、协议适配器、播放器、UI、自动化测试和真机双协议验收。

## 记录规则

1. 只记录事实、验证结果、未完成事项和下一步。
2. 不复制长日志，不把未来计划写成已完成事实。

## 2026-08-18 架构收敛与全屏施工

1. 已完成：将播放编排收敛为 `CastPlaybackRouter`、`DlnaPlaybackAdapter`、`AirPlayPlaybackAdapter` 三层，Router 独占共享 Media3 播放器和会话接入；协议适配器不再共享第二套状态机。
2. 已完成：`CastSessionCoordinator` 区分“创建新会话”和“复用当前租约”；旧代次回调会被丢弃，修复同一 AirPlay 会话轮询导致租约自失效的问题。
3. 已完成：运行时停止后保留回调装配，下一次启动重新绑定；网络播放器命令在主线程立即串行，避免旧停止回调污染新会话。
4. 已完成：全屏交接改为唯一令牌，Navigator 通过公开 Android 任务 API 管理标准窗口 / 全屏任务，并在启动失败时恢复来源任务。
5. 用户已在真实播放中完成浮窗 -> 全屏 -> 浮窗按钮往返，双向切换正常。`testDebugUnitTest assembleDebug`、项目就绪 / Skill 检查、Debug APK 覆盖安装和启动 smoke 均通过；应用进程、`CastService`、AirPlay `7000` 与 DLNA `8200` 在线。
6. 已增加版本 `1` 的全屏独占租约：系统可发现 `03歌词` provider；全屏期间 `03投屏` 对其保持真实 bound Service 连接，歌词 Overlay 在窗口系统中为不可见；退出全屏后连接消失，投屏窗口回到 `(660,90)-(1890,900)`，歌词按最新窗口状态恢复为可见顶栏。该闭环不依赖 `03歌词` 猜测前台应用，也未扩大无障碍监听范围。
7. 当前下一步：全屏主闭环已收口，按独立验收项继续 HDR、AirPlay 视频和 AirPlay 镜像兼容验证。

## 2026-08-19 用户主验收流程收敛

1. 已完成：将默认验证收敛为最低成本机器检查和直接相关单测；UI、启动、导航、输入及真实设备交互由用户按完整手测用例验收，AI 不默认执行截图、坐标点击或运行级 smoke。
2. 已完成：`scripts/install-debug-to-device.sh` 默认只覆盖安装 Debug APK；完整启动 / 进程 / 服务 / 端口检查改为显式 `--runtime-smoke`。
3. 已同步：项目 `AGENTS.md`、验证规则、验证矩阵、施工方案、产品验收口径和 AI 协作规则，避免下轮恢复旧测试门禁。
4. 已验证：Shell 语法、安装帮助入口、项目就绪检查、skills 快检和 `git diff --check` 通过；模板快检因当前仓库是已初始化项目而不适用。

## 2026-08-19 UI、安全与会话交付

1. 已完成：等待页、双栏设置页、应用内行车风险确认、协议二维码和 `S56_HQX` 档位读取；标准 `CURRENT_GEAR` 不可用时使用已取证的 `GEAR_SELECTION=11 -> P 档`兼容映射，未知值保持不可用。
2. 已完成：活动态顶部关闭只断开当前发送端并回到等待，保留 DLNA / AirPlay 广播；用户主动全屏关闭回标准浮窗，远程断开保持当前窗口模式并立即回到等待。移除媒体换片的 `2 s` 会话连续性确认，新增全屏等待态“退出全屏”按钮；播放、全屏、关闭控件统一车机触控规格，窗口交接后全屏图标按实际窗口模式重同步。
3. 已完成：Media3 `PlayerControlView` 控制桥、透明控制层、置顶 `SurfaceView` 主链和安全播放锁；未引入悬浮窗权限、无障碍服务或后台常驻。
4. 已验证：`CastSessionCoordinatorTest`、`DrivingSafetyPolicyTest`、`assembleDebug`、`bash -n scripts/install-debug-to-device.sh` 和 `git diff --check` 通过；Debug APK 已通过目标型号筛选覆盖安装到 `S56_HQX`，未启动应用。
5. 待用户手测：设置页白条返回、全屏返回后的图标、短视频自动下一条保持当前窗口、真实断开保持当前窗口、全屏等待态退出全屏、活动态关闭回等待，以及真实 DLNA / AirPlay 兼容性。HDR、AirPlay URL/HLS、镜像和不同 DLNA 媒体比例仍未完成专项验收。

## 2026-08-20 全屏交接失败复盘与修复

1. 已定位：上一版把来源任务身份传入目标 Activity 和服务，再通过 `RecentTaskInfo.taskId` 扫描任务；该字段从 API 29 才存在，Android 9 真机在全屏返回浮窗时连续触发 `NoSuchFieldError` 并终止主进程。跨任务确认和服务未绑定排队同时扩大了窗口交接主链，用户确认浮窗进入全屏的响应明显变慢。
2. 已修复：恢复来源 Activity 主导的同步公开任务切换；服务只持有三秒媒体交接令牌，不再识别、扫描或恢复任务。全屏目标提交成功后来源 Activity 只调用 `finishAndRemoveTask()` 结束自身，目标启动与来源结束使用独立失败边界，移除切换失败 Toast 和未绑定请求排队。
3. 已补充：窗口控件只在服务绑定完成且没有进行中交接时可用；策略单测覆盖“目标启动失败不结束来源”和“来源结束失败不否定已接受目标”，Android 平台规则补充 `compileSdk` 与 `minSdk` 的运行时兼容门禁。
4. 已验证：`testDebugUnitTest`、`assembleDebug`、`node scripts/check-project-ready.mjs`、`node scripts/check-skills.mjs`、`bash -n scripts/install-debug-to-device.sh` 和 `git diff --check` 通过；`lintDebug` 已确认本轮窗口代码无 `NewApi`，但仓库既有 Media3 opt-in lint 仍使全任务失败；Debug APK 已通过 `--install-only` 覆盖安装到 `S56_HQX`，未启动应用。
5. 用户已手测通过：浮窗 -> 全屏响应速度恢复；全屏 -> 浮窗不崩溃、不隐藏应用、不出现切换失败提示，播放位置、广播和全屏图标保持正确。

## 2026-08-20 商业权益、权益中心与关于页

1. 已完成：按 `03歌词` 的交互骨架补齐“权益中心”和“关于”设置分区；权益页支持服务端报价、订单详情、微信 / 支付宝二维码支付、报价变化二次确认、支付轮询、同设备恢复购买和 Pro 成功态；关于页展示构建类型对应的《03投屏用户协议》二维码。
2. 已完成：等待页接入 `Checking / Trial / Expired / Pro / Error` 五态权益摘要和购买 / 查看权益 / 重新确认入口；无权益时只拒绝媒体输出创建，不停止 `CastReceiverRuntime`，`7000 / 8200 / 1900`、mDNS、SSDP、AirPlay 与 DLNA 发现继续保持。
3. 已完成：商业状态由进程级 `CommercialEntitlementCoordinator` 统一持有，`CommercialRuntimeAccessGuard` 只向 `CastPlaybackRouter` 提供已验签准入；播放中到期或撤权先失效会话代次，再释放播放器、镜像、音频和图片输出并回到等待，不关闭窗口、不停止接收、不发布关闭窗口的会话结束事件。
4. 已完成：Debug 默认使用 Android Keystore 运行时生成的 `03cast` 专用 fixture 签名器；Release / staging 缺少可信许可证公钥、产品签名摘要或完整 HTTPS 配置时 fail closed。未写入真实私钥、token、keystore、生产环境文件或云端机密。
5. 已验证：`:app:testDebugUnitTest`（88 条通过）、`:app:assembleDebug`、`:app:assembleRelease`、`node scripts/check-project-ready.mjs`、`node scripts/check-skills.mjs`、`bash -n scripts/install-debug-to-device.sh` 和 `git diff --check` 通过；Release 构建仅证明缺省配置下可构建，不能代表正式支付已接通。
6. 已修复：服务侧撤权、配置缺失、存储失败、时钟回拨或设备不匹配统一投影为共享 `EntitlementState.Error`，等待页和设置页不再继续显示旧的 Pro 摘要；本地尚无许可证时仍保留 `Checking`，等待首次联网试用结果。
7. 已知边界：`lintDebug` 仍被仓库既有 Media3 opt-in、资源 `UseAppTint` 和 `CastService.onStartCommand` 的 `MissingSuperCall` 阻断；本轮商业 / 窗口代码未引入 `taskId`、`RecentTaskInfo` 或 API 29+ 窗口符号。未执行 runtime smoke、截图、坐标点击、真实支付、恢复购买或真实 DLNA / AirPlay 商业门禁手测。
8. 下一步：用户在 `S56_HQX` 完成试用 / 过期 / Pro / 查询错误四态、无权益仍可发现但无媒体输出、播放中撤权回等待、支付 / 恢复后下一次投屏、关于二维码和窗口关闭后 `7000 / 8200 / 1900` 释放的最小手测；真实 Apple 设备、AirPlay URL / HLS、HDR、镜像兼容性和不同 DLNA 媒体比例继续按验证矩阵验收。

## 2026-08-21 窗口切换性能优化

1. 已修正：放弃两个完整投屏 Activity / 任务长期驻留方案；目标车机已证明后台全屏任务的 HWC 视频层可能继续残留。全屏任务恢复为一次性承载窗口，返回标准浮窗后立即结束并移除。
2. 已优化：浮窗进入全屏时直接提交目标任务，不再先调用 `moveTaskToBack(true)` 串行等待 `300 ms` 退场动画；权益详情、关于页和协议二维码改为按需加载，不再进入全屏 Activity 创建主链。播放器、`CastService` 和进程级权益协调器保持唯一实例。
3. 已验证：直接相关的窗口交接、窗口策略和商业布局契约单测通过；Staging `assembleDebug` 构建成功，APK 包名为 `com.ninepointnine.desktopcast`，实际 v2 签名证书 SHA-256 为 `98740B95C30064F727B9401A851ECF2E576D5E5C38FCC318284578747BA50E2A`；已通过 install-only 覆盖安装到 `192.168.0.203:5555 / S56_HQX`，未启动应用。
4. 已验收：用户在目标车机手动验证窗口切换通过，浮窗 / 全屏往返已恢复到记忆中近似的流畅度，未再出现后台常驻全屏窗口造成的残留画面。本轮按用户要求未执行自动交互冒烟；设置、权益页、关于页与行车协议二维码仅复用布局契约测试结论，不冒充人工验收。

## 2026-08-21 哔哩哔哩窗口交接解码错误

1. 已取证并收敛根因：哔哩哔哩 DLNA `video/mp4` AVC 使用 `OMX.qcom.video.decoder.avc`；切换时先出现 `Failed to qbuf to driver`、`failed to fill output buffer`、`OMX_ErrorHardware`，随后 `MediaCodecVideoDecoderException` 与 `Surface: getSlotFromBufferLocked: unknown buffer`。没有 HTTP、DLNA SOAP、URL 失效或发送端撤流证据，故根因是 Android 9 高通 codec 在跨 Activity SurfaceView 的 BufferQueue 上异步热切输出不安全。
2. 已施工底层交接：`NetworkMediaPlayer` 为 Android 9 厂商 codec 强制 Media3 release/recreate 路径，并在窗口启动前通过播放线程 `Renderer.MSG_SET_VIDEO_OUTPUT(null)` + `blockUntilDelivered` 确认旧输出已摘除；`VideoRenderer` 同样停止旧 codec、保留有限关键帧后在目标 Surface 重建。`CastPlaybackRouter` / `CastService` 负责统一 preflight、目标失败恢复和成功提交，未增加第二播放器、固定延时或常驻窗口。
3. 已补充：窗口策略、Android 9 codec 策略、renderer detach acknowledgement、取消 / 超时恢复和同一 Holder 幂等绑定单测；代码规则、验证矩阵、长期总纲和施工方案已同步“禁止跨 BufferQueue `setOutputSurface`”的不变量。Staging 构建与目标车机覆盖安装待本轮机器检查完成后记录，交互结果由用户手测确认。

## 2026-08-20 等待页商业呈现调整

1. 已完成：Trial 等待态移除“当前可以投屏”，改为显示剩余试用时间和服务端报价驱动的长购买广告；原价在广告中使用删除线，点击整句进入购买流程。
2. 已完成：Expired 等待态改为红色“无法投屏”，灰色说明为“试用已到期，请购买Pro以继续”，并复用 Trial 的长购买广告；Pro 等待态加入与 03 歌词同源的皇冠图标，只显示“Pro 权益生效中 · 永久”。
3. 已完成：设置页“03投屏”标题右侧角标按 03 歌词源码改为相对标题定位的同构布局，保留既有权益状态颜色和文字映射。
4. 价格边界：广告只展示 `CommercialUiState.quote` 的服务端原价 / 活动价；云端当前正式 03cast 为 `¥65.00 / ¥39.00`，Debug fixture 为 `¥0.02 / ¥0.01`，客户端不固化 `69 / 39`。
5. 已验证：`:app:testDebugUnitTest --rerun-tasks` 通过；上一版 Debug APK 已覆盖安装并按用户要求拉起，等待本轮广告层级调整后重新覆盖安装。

## 2026-08-20 等待页双锚点布局调整

1. 已完成：主等待状态组“Logo + 03投屏 + 等待投屏”与底部权益 / 广告 / 设置组彻底分离；主状态组固定向上位于中上部，底部组以设置按钮为固定锚点。
2. 已完成：Checking、Trial、Expired、Pro、Error 只在底部辅助组内切换内容；连接进度条使用不可见占位保持主状态组位置稳定。
3. 待验证：商业布局单测、Debug 构建和车机覆盖安装；主状态视觉间距由用户在 `S56_HQX` 实体屏幕完成最终验收。

## 2026-08-20 等待页广告层级调整

1. 已完成：长广告作为 Trial / Expired 等待态主体，辅助文案移动到广告按钮下方；Expired 直接替换主等待标题为红色“无法投屏”。
2. 已完成：服务端原价删除线改为同色系蓝灰，活动价仍使用蓝紫强调色；Pro 改为按正文实际字面高度对齐的皇冠内联富文本，并从主等待内容抽离到设置入口上方；长广告点击先进入权益中心首页。
3. 已补充：等待页状态布局与首页跳转行为的布局契约测试。
4. 已验证：本轮 Debug APK 已通过 `--install-only` 覆盖安装到 `S56_HQX`，未启动应用。

## 2026-08-20 包名与签名身份迁移

1. 已确认：03投屏最终 Android 包名为 `com.ninepointnine.desktopcast`；`productId=03cast`、SKU `03cast_pro_device_cny`、运行标识 `icar03` 保持不变。
2. 已完成：Kotlin / debug / release / test 源码目录、Manifest、构建配置、ProGuard、安装脚本、源码路径契约、JNI native 导出符号和 cloud 03cast 契约同步迁移；`com.tcrrry.icar.window.*` 与 `com.tcrrry.icar.surface.*` 跨应用协议常量保持不变。
3. 已生成：仓库外独立 staging / production RSA 4096 签名材料，摘要记录于 `/Users/q/Desktop/secrets/03cast-ninepointnine-signing-summary.txt`；旧包证书与目录未覆盖。
4. 已完成：production 签名入口改为 `deviceCommerceProductionSigningPropertiesFile` 外部注入，并将 `*.jks`、`*.keystore`、`keystore.properties`、`signing.properties` 加入忽略规则。
5. 待完成：cloud 注入新证书摘要与 trust bundle；新包在 `S56_HQX` 的 `CAR_POWERTRAIN` 授权复验；新包单独的真实 DLNA / AirPlay、商业门禁和端口释放验收。
6. 本轮边界：未安装新包、未卸载旧包、未清数据、未修改车机设置、未部署 cloud、未执行真实支付。

## 2026-08-21 目标 Surface 接管确认施工

1. 已完成：保留真实双 Activity / 双任务视觉模型，窗口令牌提交从“目标 Surface 有效”升级为“目标 Surface 有效且 renderer 已确认同一 Surface 输出消息在播放线程完成”；网络视频记录 Surface 身份与输出代次，镜像确认当前 Holder / Surface 身份。
2. 已完成：目标接管失败、旧令牌、取消和超时均不会提交来源退出动作；网络媒体 `surfaceChanged` 仍不重复提交同一 Holder，Android 9 高通 codec 继续使用摘除后释放 / 重建路径，未增加 Bilibili 特判、固定延时、第二播放器或协议重连。
3. 已同步：`docs/architecture/rules/code.md`、`docs/architecture/rules/testing.md`、项目长期总纲、V1 施工方案和验证矩阵均明确 renderer output acknowledgement 为窗口交接门槛。
4. 已验证：`:app:testDebugUnitTest --rerun-tasks` 共 102 条通过、Staging `assembleDebug` 通过、项目就绪 / Skill 快检、`git diff --check` 和安装脚本语法检查通过；APK 包名为 `com.ninepointnine.desktopcast`，v2 签名证书 SHA-256 为 `98740b95c30064f727b9401a851ecf2e576d5e5c38fcc318284578747ba50e2a`，已通过 `install-only` 覆盖安装到 `S56_HQX`，未启动应用。`lintDebug` 仍被仓库既有 28 个错误和 84 个警告阻断，本轮新增交接路径无 `NewApi` 错误。真实视觉全屏、哔哩哔哩 DLNA 播放连续性和无残影由用户手测确认。

## 2026-08-21 哔哩哔哩窗口切换稳定性验收

1. 用户在目标车机多次手测哔哩哔哩 DLNA 播放中的“浮窗 -> 全屏 -> 浮窗”，确认不再出现“该内容暂时无法播放”，播放不中断，进程和接收服务保持正常。
2. 切换期间仍会出现数秒级黑屏，但声音连续，随后画面恢复；该现象与 Android 9 高通 codec 在跨 Activity BufferQueue 上安全摘除并重建、等待下一组视频关键帧一致，不是网络断流、DLNA URL 失效或播放器崩溃。
3. 本轮最终验收口径以稳定性优先：允许这段首帧等待，禁止播放中断、错误页、不可恢复黑屏、旧全屏残影和后台接收异常。零黑屏需要改为同一 Activity / 同一 Surface / 同一 codec 的窗口主链，超出本轮已接受范围，暂不施工。
