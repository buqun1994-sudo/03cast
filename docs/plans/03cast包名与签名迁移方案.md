# 03cast 包名与签名迁移方案

## 1. 目标与最终身份

本方案记录 03投屏 从历史包名迁移到 9.9 studio 品牌身份后的长期真值。新 APK 不再沿用历史包名或历史签名，最终身份固定为：

```text
productId = 03cast
sku = 03cast_pro_device_cny
packageName = com.ninepointnine.desktopcast
runtimeIdentifier = icar03
displayName = 03投屏
```

`com.tcrrry.icar.window.*` 与 `com.tcrrry.icar.surface.*` 是车机跨应用窗口 / 避让协议常量，不属于应用包名，必须原样保留。

## 2. 迁移范围

1. Android `namespace`、`applicationId`、Kotlin 源码包、测试 / debug / release 源码目录、Manifest 全屏 task affinity、ProGuard keep 规则和安装脚本统一使用 `com.ninepointnine.desktopcast`。
2. `app/src/main/cpp/native_bridge.cpp` 的 JNI 导出符号同步迁移到 `Java_com_ninepointnine_desktopcast_*`；这是运行时必要条件，不能只迁移 Kotlin 包。
3. 商业产品契约、云端商品目录、设备商业 API 测试和协议文档同步使用新包名；`productId`、SKU、运行标识和协议 wire format 不变。
4. 所有源码路径契约测试、架构锚点和施工方案使用新物理路径；旧包名只允许出现在本方案的历史追溯和旧证书记录中。

## 3. 签名边界

旧签名目录与证书保持原样，不覆盖、不删除、不用于新包准入：

```text
旧 staging SHA-256 = C38830948087726A035D7CCDC692EC80B1CE0775938CE0CFFB1D26692FB44E9C
旧 production SHA-256 = 3F45A9A5C594F981E405F4A84159969E98EA4578E634AE6F888BC41D9FBD9041
```

新签名为独立 RSA 4096 / SHA-256withRSA 证书，材料只在仓库外保存：

| 环境 | 材料目录 | alias | 证书 SHA-256 |
|---|---|---|---|
| staging | `/Users/q/Desktop/secrets/测试环境/03cast-staging-signing-ninepointnine/` | `03cast-staging-ninepointnine` | `98740B95C30064F727B9401A851ECF2E576D5E5C38FCC318284578747BA50E2A` |
| production | `/Users/q/Desktop/secrets/正式环境/03cast-release-signing-ninepointnine/` | `03cast-release-ninepointnine` | `14E4A7CDF1481AFDB871487AA830BB0DC28910C0BA681693F11F9F1DD2FD4423` |

目录权限为 `0700`，keystore、属性文件和摘要文件权限为 `0600`。摘要文件不含口令、私钥或许可证材料：
`/Users/q/Desktop/secrets/03cast-ninepointnine-signing-summary.txt`。

## 4. 构建注入

`app/build.gradle.kts` 不再读取仓库根 `keystore.properties`。签名属性通过仓库外 Gradle property 注入：

```text
-PdeviceCommerceStagingSigningPropertiesFile=/Users/q/Desktop/secrets/测试环境/03cast-staging-signing-ninepointnine/signing.properties
-PdeviceCommerceProductionSigningPropertiesFile=/Users/q/Desktop/secrets/正式环境/03cast-release-signing-ninepointnine/signing.properties
```

staging 构建还必须同时注入 cloud 返回的 HTTPS 地址、`keyId`、许可证公钥和新 staging 证书摘要；缺少任一项即 fail closed。production 同样由发布环境注入 production trust bundle 和新 production 证书摘要。仓库不保存属性文件、keystore、许可证私钥或云端 secret。

## 5. Cloud 接线与剩余门禁

cloud 已同步 03cast 商品目录、环境配置、设备商业测试、协议文档和产品说明中的包名。尚未执行部署或 secret 轮换。

在新 APK 进入真实商业验收前必须完成：

1. 将 production SHA-256 `14E4A7...4423` 注入 `DEVICE_COMMERCE_03CAST_SIGNING_CERT_SHA256`，并由 cloud 生成 / 注入对应 trust bundle。
2. 将 staging SHA-256 `98740B...0E2A` 与 staging trust bundle 注入测试环境。
3. 在 `S56_HQX` 以新包名完成 `CAR_POWERTRAIN` 授权复验；旧包已验证的授权不能推定新包自动继承。
4. 使用新包单独完成广播、权益门禁、DLNA / AirPlay 和窗口关闭释放验收。新旧包不可同时运行，因为会争用 `7000 / 8200 / 1900`。

本轮不安装新包、不卸载旧包、不清数据、不修改车机设置、不部署 cloud、不执行真实支付。

## 6. 验证标准

机器检查必须确认：

1. `aapt dump badging` / APK 元数据中的包名为 `com.ninepointnine.desktopcast`。
2. Kotlin JNI 声明与 native 导出符号前缀一致，且源码中不存在误留的 `Java_com_tcrrry_desktopcast_*`。
3. Debug 单测、Debug / Release 构建、项目就绪检查、Skill 检查、安装脚本语法和 `git diff --check` 通过。
4. cloud 设备商业测试和 `git diff --check` 通过。

剩余人工门禁是新包在 `S56_HQX` 的权限复验，以及真实 Apple / DLNA 设备和端口释放验收；这些不能由静态构建替代。
