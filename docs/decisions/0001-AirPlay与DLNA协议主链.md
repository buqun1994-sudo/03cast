# 0001：AirPlay 与 DLNA 协议主链

状态：已接受

## 背景

03投屏需要在 Android 9 车机上被主流 DLNA 发送端与 Apple 设备直接发现，并完成真实媒体播放或非 DRM 屏幕镜像。仅实现设备发现、旧版 AirPlay URL 推送或原车残留入口都不能形成可验收闭环。

## 决策

1. AirPlay 使用 `jqssun/android-airplay-server` 的 UxPlay Android 主链，保留其协议、配对、镜像、音频、HLS 回调与硬件解码路径；删除上游 Compose、设置、日志、下载、后台自启与手机 / TV 页面。
2. DLNA 在项目内实现可测试的 DMR 适配器，支持 SSDP、设备描述、`AVTransport`、`RenderingControl`、`ConnectionManager`、SOAP XML 与 GENA；不照搬仅能发现的最小示例。
3. AirPlay URL / HLS 与 DLNA URL 共用 Media3 播放器；AirPlay 镜像继续使用 MediaCodec，AirPlay 音频继续使用原生低延迟输出。
4. `CastSessionCoordinator` 是唯一活动会话 owner；协议适配器不能直接驱动界面，也不能建立第二套播放器状态机。

## 本轮补充决策

1. AirPlay `/info` 的显示能力按目标车机物理面板（`1920x1080@60`，含校验过的 EDID）对外宣告；`maxFPS` 由物理刷新率与硬件解码能力共同取上限。目标 `S56_HQX` 的厂商能力文件确认 1080p H.264 / HEVC 硬件解码高于 `60 fps`，当前因此宣告 `60`，发送端仍可自主选择更低帧率。显示 UUID 首次按车机硬件生成并持久化，避免 macOS 复用其它接收器的显示缓存，也避免网络接口变化触发新显示身份。本地 MediaCodec 的尺寸上限只约束解码启动，不再用来缩小发送端的显示协商结果。RTP 编码帧尺寸同时用于解码器和显示比例，源桌面尺寸只保留为诊断信息。
2. AirPlay 启动时枚举全部常规视频解码器，生成一份不可变的 AVC / HEVC 候选快照；`maxFPS`、H.265 开关与镜像帧到达后的 codec 选择共用该快照。Android 9 厂商静态 codec metadata 只作排序和诊断提示，不能作为 Surface 解码硬门禁：目标车机取证的 Qualcomm codec 即使报告错误尺寸或未列出 `COLOR_FormatSurface`，也必须保留，按 codec 名称在真实 `Surface` 上逐个执行 `configure/start`；可选实时参数导致拒绝时，先用保守格式重试同一 codec。`maxFPS` 将面板刷新率与编码能力分开维护，未知静态帧率不得降成 30。软件解码只作为最长边不超过 `1280`、最短边不超过 `720` 的兼容路径；启动时尚未观测到帧率可先尝试小流，观测超过 `30 fps` 后停止软件路径；1080p 镜像硬件启动失败不允许静默回退到 CPU 解码，避免以黑屏、卡顿或过热掩盖硬件链路失败。
3. AirPlay 镜像的会话边界由 native `mirror_video_running` 回调确认；镜像开始、尺寸、视频帧和结束回调统一携带单调递增的原生镜像流令牌，旧连接的迟到事件不能覆盖新连接。开始与尺寸控制回调在返回 native 线程前完成主线程归并，避免首个 SPS/PPS/VPS + 随机访问帧被丢弃；HTTP 连接销毁只做可取消的短暂确认兜底。MediaCodec 必须直接输出到不透明置顶 `SurfaceView`，不经过 `SurfaceTexture`、EGL 或 OpenGL 中转；编码尺寸变化在下一关键帧重建 Codec，并通过 `SurfaceHolder.setFixedSize(encodedWidth, encodedHeight)` 同步 producer buffer geometry，Activity 的浮窗 / 全屏布局只负责显示缩放。跨 Activity 交接优先使用 `setOutputSurface`，失败时保留同一流的有界完整启动帧并重建 Codec；Android 9 不得默认携带未经探针确认的 `KEY_OPERATING_RATE` 等可选实时参数。
4. DLNA 的 `protocolInfo` 在 XML 适配层保留原文，进入 Media3 前由统一 MIME resolver 识别 HLS 与常见容器；仅对 DLNA 视频 / 未知类型在 progressive extractor 全失败时执行一次 HLS 重试，避免把协议解析、播放器探测和 Activity 状态混在一起。

## 拒绝路线

1. 拒绝只支持早期 AirPlay URL 推送的旧 Java 接收器：不支持现代屏幕镜像与完整音频路径。
2. 拒绝把原车隐藏媒体入口作为依赖：已观察到原车 DLNA 描述地址与状态不可用，且没有可用 AirPlay 接收能力。
3. 拒绝手写 AirPlay 加密、配对和镜像协议：维护成本与兼容风险不可接受。
4. 拒绝以 WebView 或远端转码服务承接媒体：会增加网络依赖、延迟和隐私边界，且无法替代局域网投屏协议。

## 结果

1. 项目组合程序按 GPL-3.0 分发并保留全部第三方来源与许可证。
2. DRM / FairPlay 保护内容不承诺播放；普通镜像、音频与网络媒体属于 V1。
3. 原生构建只产出目标车机使用的 `arm64-v8a`，降低构建时间与 APK 体积；其它 ABI 需要独立决策后再加入。
