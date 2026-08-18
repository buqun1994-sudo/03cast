# 03投屏

Android 9 iCAR 车机上以最简界面稳定接收 DLNA 与 AirPlay 的独立投屏器。

## 1. 当前范围

1. 同时公开 DLNA 与 AirPlay 接收入口。
2. 在 iCAR 标准右侧浮窗中播放网络媒体、AirPlay 屏幕镜像和音频。
3. 关闭应用后停止接收，不建立媒体库或常驻后台。

详细边界见 `docs/product/产品需求基线.md`。

## 2. 验证

1. 项目就绪：`node scripts/check-project-ready.mjs`
2. Android 工程建立后：`./scripts/gradlew-jdk17.sh testDebugUnitTest assembleDebug`
3. 目标车机安装：`./scripts/install-debug-to-device.sh`
