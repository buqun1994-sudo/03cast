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

1. 触发条件：任何需要连接目标车机的 ADB 操作。动作：每次先用首选 ADB 运行 `adb devices -l` 快速枚举；若未发现已验证车机，立即对当前局域网做一次有界、短超时的 TCP 候选枚举，只有 IP 的候选默认补端口 `5555`，历史 `ANDROID_DEVICE_SERIAL` 只作上次成功地址提示。候选连接后以显式 serial 核对状态为 `device`、型号为 `S56_HQX`、SDK 为 `28`。验证：本轮选定 serial 的设备身份匹配。边界：只有现状枚举与候选连接均失败才报告离线；禁止高频、无界或跨网段持续扫描，也禁止并行启动冲突 ADB Server。
2. 触发条件：已确认目标车机。动作：将本轮 serial 显式传给 `ANDROID_DEVICE_SERIAL` 后再运行安装或诊断脚本。验证：脚本输出的目标型号、SDK 和分辨率匹配。边界：ADB 只用于开发、安装与验证，不进入投屏运行主链；安装、运行 smoke 和破坏性动作仍分别服从现有授权门禁。

## 4. Release 版本

1. 触发条件：准备测试或 Release 版本。动作：所有变体读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`。Debug/staging 只追加 `applicationIdSuffix=".test"` 和 `versionNameSuffix="-test"`，不创建第二套版本文件。验证：`node scripts/bump-release-version.mjs --check`、按目标变体核对 APK 包名、版本和签名。边界：不把版本递增等同于部署、上线或车机安装。
