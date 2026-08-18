# 0001：AirPlay 与 DLNA 协议主链

状态：已接受

## 背景

03投屏需要在 Android 9 车机上被主流 DLNA 发送端与 Apple 设备直接发现，并完成真实媒体播放或非 DRM 屏幕镜像。仅实现设备发现、旧版 AirPlay URL 推送或原车残留入口都不能形成可验收闭环。

## 决策

1. AirPlay 使用 `jqssun/android-airplay-server` 的 UxPlay Android 主链，保留其协议、配对、镜像、音频、HLS 回调与硬件解码路径；删除上游 Compose、设置、日志、下载、后台自启与手机 / TV 页面。
2. DLNA 在项目内实现可测试的 DMR 适配器，支持 SSDP、设备描述、`AVTransport`、`RenderingControl`、`ConnectionManager`、SOAP XML 与 GENA；不照搬仅能发现的最小示例。
3. AirPlay URL / HLS 与 DLNA URL 共用 Media3 播放器；AirPlay 镜像继续使用 MediaCodec，AirPlay 音频继续使用原生低延迟输出。
4. `CastSessionCoordinator` 是唯一活动会话 owner；协议适配器不能直接驱动界面，也不能建立第二套播放器状态机。

## 拒绝路线

1. 拒绝只支持早期 AirPlay URL 推送的旧 Java 接收器：不支持现代屏幕镜像与完整音频路径。
2. 拒绝把原车隐藏媒体入口作为依赖：已观察到原车 DLNA 描述地址与状态不可用，且没有可用 AirPlay 接收能力。
3. 拒绝手写 AirPlay 加密、配对和镜像协议：维护成本与兼容风险不可接受。
4. 拒绝以 WebView 或远端转码服务承接媒体：会增加网络依赖、延迟和隐私边界，且无法替代局域网投屏协议。

## 结果

1. 项目组合程序按 GPL-3.0 分发并保留全部第三方来源与许可证。
2. DRM / FairPlay 保护内容不承诺播放；普通镜像、音频与网络媒体属于 V1。
3. 原生构建只产出目标车机使用的 `arm64-v8a`，降低构建时间与 APK 体积；其它 ABI 需要独立决策后再加入。
