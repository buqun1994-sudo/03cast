# 运维规则

## 1. 默认规则

1. local、test、staging、production 必须明确区分，不能用测试通过冒充生产可用。
2. 部署和上线必须支持可追溯入口、可观测输出和回滚说明。
3. 能 dry-run 的操作不直接真实写入。
4. production 操作必须人工确认。

## 2. 待项目补齐

1. 环境列表和入口。
2. 部署命令。
3. 回滚方式。
4. 监控和告警。

## 3. 车机连接发现

1. 触发条件：任何需要连接目标车机的电脑端 ADB 操作。动作：无条件先运行唯一入口 `node scripts/find-vehicle-adb.mjs --serial-only`；入口先复用已连接目标，否则排除 VPN / 虚拟网卡后，对物理私有 IPv4 网段执行 TCP `5555` 并发探测（每网段最多 `/24`、总候选最多 `512`、并发上限 `254`、单地址 `350ms`），只对端口命中项执行 ADB 握手。验证：入口返回且核对为 `device / S56_HQX / SDK 28` 的唯一 serial。边界：同一局域网只代表网络前缀相同；历史地址不是在线真值，入口失败前禁止人工看旧 IP、路由、ARP、逐地址连接或报告离线，失败后才允许诊断；禁止并行 ADB Server 和大于 `/24` 的无界扫描。
2. 触发条件：统一入口已确认目标车机。动作：执行 `ANDROID_DEVICE_SERIAL="$(node scripts/find-vehicle-adb.mjs --serial-only)" ./scripts/install-debug-to-device.sh --install-only`，或将同一返回值显式传给诊断脚本。验证：脚本输出的目标型号、SDK 和分辨率匹配。边界：ADB 只用于开发、安装与验证，不进入投屏运行主链；安装、运行 smoke 和破坏性动作仍分别服从现有授权门禁。
3. 触发条件：同一 `versionName / versionCode` 下连续覆盖安装 Debug 包并据此判断某次源码修改是否生效。动作：构建后记录 APK SHA-256，安装后同时核对目标包的安装更新时间，并在关键链路使用本轮新增的唯一日志文案；禁止只看版本号判断车机运行了最新代码。验证：本地待装 APK 摘要、安装命令成功时间、设备包更新时间和关键日志四项属于同一次构建。边界：该规则只用于未递增版本的开发包诊断，不替代正式 / staging 的版本递增与产物台账。

## 4. Release 版本

1. 触发条件：准备测试或 Release 版本。动作：所有变体读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`。Debug/staging 只追加 `applicationIdSuffix=".test"` 和 `versionNameSuffix="-test"`，不创建第二套版本文件。验证：`node scripts/bump-release-version.mjs --check`、按目标变体核对 APK 包名、版本和签名。边界：不把版本递增等同于部署、上线或车机安装。
