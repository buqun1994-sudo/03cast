# 项目进度

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
2. 已完成：活动态顶部关闭只断开当前发送端并回到等待，保留 DLNA / AirPlay 广播；全屏关闭或真实远程断开先回标准浮窗。媒体换片使用 `2 s` 会话连续性确认，避免短视频下一条触发全屏跳转；播放、全屏、关闭控件统一车机触控规格，窗口交接后全屏图标按实际窗口模式重同步。
3. 已完成：Media3 `PlayerControlView` 控制桥、透明控制层、置顶 `SurfaceView` 主链和安全播放锁；未引入悬浮窗权限、无障碍服务或后台常驻。
4. 已验证：`CastSessionCoordinatorTest`、`DrivingSafetyPolicyTest`、`assembleDebug`、`bash -n scripts/install-debug-to-device.sh` 和 `git diff --check` 通过；Debug APK 已通过目标型号筛选覆盖安装到 `S56_HQX`，未启动应用。
5. 待用户手测：设置页白条返回、全屏返回后的图标、短视频自动下一条保持全屏、真实断开回浮窗、活动态关闭回等待，以及真实 DLNA / AirPlay 兼容性。HDR、AirPlay URL/HLS、镜像和不同 DLNA 媒体比例仍未完成专项验收。
