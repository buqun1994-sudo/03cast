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

## 3. Release 版本

1. 触发条件：准备 Release 版本。动作：读取仓库根 `release-version.properties`；未指定版本时运行 `node scripts/bump-release-version.mjs` 递增 patch 并同步递增 `releaseVersionCode`，有明确版本时传入 `--version`；构建不会自行改写版本文件，Debug / Staging 不读取 Release 版本覆盖。验证：`node scripts/bump-release-version.mjs --check`、Release APK 元数据和签名核对。边界：不把版本递增等同于部署、上线或车机安装。
