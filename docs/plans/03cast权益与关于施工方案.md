# 03cast 权益与关于施工方案

## 1. 施工目标

1. 为 `com.ninepointnine.desktopcast` 补齐与 03 歌词同构的权益中心、支付 / 恢复购买流程和关于页。
2. 在等待页展示 `Checking / Trial / Expired / Pro / Error`，让用户知道当前能否开始投屏以及下一步动作。
3. 只有通过统一商业准入的媒体会话才能创建播放器、解码器、音频或镜像输出；无权益时仍保持 AirPlay、DLNA、mDNS、SSDP 广播和发现能力。
4. 不改变已验收的窗口交接、全屏切换、行车保护、协议 wire format 和 native AirPlay 主链。

本方案是本轮施工的设计真值。代码、测试和后续对话均以本文件的物理锚点与不变量为准。

## 2. 产品与云端契约

云端真值文件：

- `/Users/q/Documents/Projects/cloud/products/03cast/commerce-products.json`
- `/Users/q/Documents/Projects/cloud/products/03cast/README.md`
- `/Users/q/Documents/Projects/cloud/apps/telemetry-api-domestic/src/commerce-product-catalog.js`
- `/Users/q/Documents/Projects/cloud/apps/telemetry-api-domestic/src/device-commerce-service.js`

客户端契约固定为：

```text
productId = 03cast
sku = 03cast_pro_device_cny
packageName = com.ninepointnine.desktopcast
runtimeIdentifier = icar03
locale = zh-CN
deviceLimit = 1
```

商品原价、活动折扣、最终金额、支付方式和订单有效期全部以服务端报价为准，客户端不得写死金额或活动码。云端正式商品为 `6500` 分、当前正式活动成交价为 `3900` 分；staging fixture 为 `2` 分原价、`1` 分成交。云端支持微信原生支付、支付宝二维码、设备绑定的一次性 Pro 权益、同设备恢复和撤权。

许可证验证沿用 Device Commerce 的签名协议：客户端只保留公钥和 `keyId`，私钥由 cloud 环境管理。03 投屏使用独立的产品 / 包名 / 应用签名摘要 / 设备密钥 / 安全存储记录；不复用 03 歌词的产品身份和本地许可证数据。若环境缺少 trust bundle，使用 cloud 的生成流程补齐，输出必须在仓库外。

## 3. 根因与物理定位

1. [MainActivity.kt](/Users/q/Documents/Projects/03投屏/app/src/main/java/com/ninepointnine/desktopcast/MainActivity.kt) 施工前只有 `RECEIVER`、`SAFETY` 两个设置分区，没有商业状态、支付页面和关于页面。
2. [CastService.kt](/Users/q/Documents/Projects/03投屏/app/src/main/java/com/ninepointnine/desktopcast/service/CastService.kt) 负责接收服务生命周期；[CastReceiverRuntime.kt](/Users/q/Documents/Projects/03投屏/app/src/main/java/com/ninepointnine/desktopcast/service/CastReceiverRuntime.kt) 是 7000、8200、1900、mDNS 和唤醒锁 owner。
3. [CastPlaybackRouter.kt](/Users/q/Documents/Projects/03投屏/app/src/main/java/com/ninepointnine/desktopcast/service/CastPlaybackRouter.kt) 是唯一媒体输出 owner，`beginSession`、`beginAirPlayMirrorSession`、`ensureSession` 是所有输出创建入口。
4. `CastSessionCoordinator` 只表达投屏会话状态，不吸收商业页面状态。商业权益使用独立 coordinator / access gate，通过监听与适配器影响 Router。

## 4. 施工边界与文件锚点

### 4.1 商业领域层

从 `/Users/q/Documents/Projects/03lyrics/app/src/main/kotlin/com/tcrrry/desktoplyrics/commercial/` 迁移并改造以下主链到：

```text
app/src/main/java/com/ninepointnine/desktopcast/commercial/
```

核心类型包括 `CommercialModels`、`CommercialAccess`、`CommercialSecurity`、`CommercialStateMachine`、`CommercialEntitlementCoordinator`、`CommercialController`、`CommercialRuntime`、`DeviceCommerceApi`、`DeviceCommerceLicense`、`CloudDeviceCommercialGateway`、`AndroidDeviceIdentity`、`AndroidPackageIdentity`、`AndroidSecureCommercialStore`、`RemotePaymentQrLoader`。

所有新 Kotlin 文件从本仓库 [MainActivity.kt](/Users/q/Documents/Projects/03投屏/app/src/main/java/com/ninepointnine/desktopcast/MainActivity.kt) 复制 ASCII 包声明和 UTF-8 文件头；目标包名为 `com.ninepointnine.desktopcast` 或其 `commercial` / `service` 子包。不得根据终端乱码重建中文文本。

### 4.2 构建与信任层

修改 `/Users/q/Documents/Projects/03投屏/app/build.gradle.kts`，新增：

- `DEVICE_COMMERCE_ENVIRONMENT`
- `DEVICE_COMMERCE_API_BASE_URL`
- `DEVICE_COMMERCE_LICENSE_KEY_ID`
- `DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64`
- `DEVICE_COMMERCE_EXPECTED_SIGNING_CERT_SHA256`

Debug 默认 `fixture`，可用 Gradle property 显式切换 staging；Release 固定 production。签名属性文件、应用 keystore、服务端私钥和真实环境文件全部在仓库外或密钥系统中，仓库只保留读取逻辑和字段名。

变体实现文件：

```text
app/src/debug/java/com/ninepointnine/desktopcast/commercial/CommercialRuntimeFactory.kt
app/src/release/java/com/ninepointnine/desktopcast/commercial/CommercialRuntimeFactory.kt
```

Debug fixture 在 Android Keystore 中运行时生成 `03cast` 专用测试签名器，并支持试用、过期、Pro、撤权、支付待定 / 成功等可重复场景；不得携带真实许可证或生产密钥。

### 4.3 设置与等待页

修改 `/Users/q/Documents/Projects/03投屏/app/src/main/res/layout/activity_main.xml`，新增四段显式设置选择：

```text
RECEIVER / SAFETY / COMMERCIAL / ABOUT
```

新增布局：

```text
app/src/main/res/layout/content_settings_commercial.xml
app/src/main/res/layout/content_settings_about.xml
app/src/main/res/layout/view_cast_commercial_summary.xml
app/src/main/res/layout/view_cast_commercial_waiting.xml
```

商业页面保持三页互斥：`ENTITLEMENT`、`ORDER`、`QR`。摘要卡在非 Pro 时可见，Pro 时整个容器 `GONE`；等待页只放状态、结果文案和一个最小购买入口，不嵌入完整支付表单。

等待页商业呈现规则固定为：

1. `Trial` 以报价驱动的长广告按钮“现在购买Pro，仅需[原价删除线] [活动价]”作为主体，试用剩余文案置于广告按钮下方；不重复显示“当前可以投屏”，点击整句只打开权益中心首页。
2. `Expired` 直接将主等待标题“等待投屏”替换为红色“无法投屏”，不在商业面板重复显示该结果；长广告作为主体，灰色说明“试用已到期，请购买Pro以继续”置于广告按钮下方。
3. `Pro` 复用 03 歌词的 `ic_commercial_pro_crown` 皇冠资源，按正文实际字面高度裁定可见笔画后通过内联 `ReplacementSpan` 与“Pro 权益生效中 · 永久”组成同一行富文本；该权益行从主等待内容中抽离，固定放在“设置”入口上方；不显示“当前可以投屏”或“查看权益”。等待页所有权益状态共用两个固定锚点：主状态组“图标 -> 03投屏 -> 等待投屏”固定在中上部，底部辅助组“权益信息 / 广告 -> 设置”固定在偏下部；权益状态变化只替换辅助组内容。
4. 长广告的原价、活动价和币种只能来自 `CommercialUiState.quote` 的服务端报价；原价删除线使用同色系低饱和蓝灰，活动价保持强调蓝紫；客户端不得硬编码 `69 / 39` 或任何其它金额。当前 cloud 03cast 正式报价为 `¥65.00 / ¥39.00`，Debug fixture 仍为 `¥0.02 / ¥0.01`。

设置页左栏标题必须按 03 歌词源码复刻：`RelativeLayout` 标题容器、居中的 `settings_title_text`，以及相对标题基线定位、同尺寸同色板的 `settings_entitlement_badge`；不得另造一套角标几何或颜色。

资源使用 `cast_commercial_*` 前缀并沿用目标项目 `cast_*` 颜色、尺寸和卡片几何。关于页二维码复用现有 `TermsQrCodeFactory` 与 `BuildConfig.TERMS_URL`。

### 4.4 投屏运行门禁

新增：

```text
app/src/main/java/com/ninepointnine/desktopcast/commercial/CommercialRuntimeAccessGuard.kt
app/src/main/java/com/ninepointnine/desktopcast/service/CastCommercialAccessAdapter.kt
app/src/main/java/com/ninepointnine/desktopcast/CastCommercialWaitingRenderer.kt
```

`CommercialRuntimeAccessGuard` 只缓存已验证的 `Allowed`；试用按签名的 `24h` 短租约和固定七天 `trialEndsAt` 安排一次性复核，永久 PRO 没有本地过期或离线宽限回调。`CastCommercialAccessAdapter` 将商业决定映射为 Router 的授权、清权和输出释放，不让商业包依赖协议实现。

## 5. 运行流程与微观规则

```text
服务启动
  -> 先启动 7000 / 8200 / 1900 / mDNS
  -> 先读取并验签本地签名权益
  -> 异步通过 purpose=check 调用 license/check
  -> Trial / Pro：授权新媒体会话
  -> 无权益 / Expired / Error：保持 WAITING，拒绝输出但继续广播

播放中到期或撤权
  -> 清除商业授权
  -> 使当前 session generation 失效
  -> 释放播放器、镜像、音频和协议输出
  -> 回到 WAITING
  -> 不调用 CastReceiverRuntime.stop()
```

权益决定：

| 状态 | 新会话 | 说明 |
|---|---:|---|
| `Checking` | 拒绝 | 在本地决定完成前不创建输出 |
| `Trial` | 允许 | 只接受服务端签名的试用边界 |
| `Pro` | 允许 | `validity=permanent`，没有本地过期或离线宽限边界 |
| `Expired` | 拒绝 | 显示购买 Pro |
| `Error` | 拒绝 | 若仍有有效本地签名权益，则保留该权益而非伪造试用 |

安全和时间边界沿用 03 歌词已验证常量：许可证签发时间偏差 5 分钟、试用时钟回拨容忍 5 分钟；试用单张许可证最长 `24h`，固定七天 `trialEndsAt` 为最终边界；挑战 / 报价默认 300 秒、订单默认 600 秒、支付轮询由服务端返回并限制在 1 到 10 秒。

`CastPlaybackRouter.beginSession`、`beginAirPlayMirrorSession`、`ensureSession` 必须先判断商业准入，再执行现有行车保护。商业拒绝不能调用 `stopCasting`、不能进入 `RECOVERABLE_ERROR`、不能触发行车倒计时、不能发布会关闭窗口的 session-end 事件。协议适配器沿用现有错误响应和空输出路径，不修改 DLNA / AirPlay wire format。

当 `license/check` 得到撤权、配置缺失、存储失败、时钟回拨或设备不匹配时，`CommercialEntitlementCoordinator` 必须把拒绝投影为共享 `EntitlementState.Error` 快照并通知设置页 / 等待页；一般网络失败只记录待复核并保留仍有效的本地凭证，但已明确 `device_key_mismatch` 时，恢复成功并完成新许可证验签持久化前不得把旧凭证作为运行时授权回退。`active` 不签发新许可证、不生成 `licenseId` 或改写本地 bytes；只有本地尚无许可证时才保留 `Checking`，等待首次联网试用查询给出权威结果。

购买或恢复成功后只恢复 gate；本轮不自动重连当前发送端，下一次发送端请求生效。

## 6. 绝对不能碰

1. `CastWindowNavigator`、`CastWindowHandoff` 及本轮已修复的 Android 9 窗口交接主链。
2. `app/src/main/cpp/` native AirPlay / FFmpeg 源码。
3. `CastReceiverRuntime` 的广播启动 / 停止语义和协议描述格式。
4. `03desktop`、`03lyrics` 源码。
5. 生产私钥、真实 token、keystore、`.env` 和本地部署配置。

## 7. 验证闭环

机器必须通过：

1. 商业验签、产品 / 包名 / 设备公钥 / 应用签名摘要隔离。
2. 无许可证、试用、过期、Pro、撤权、时钟回拨、网络失败和存储失败。
3. Activity 与 CastService 共用 coordinator，刷新单飞且状态可恢复。
4. 报价变化二次确认、订单轮询、支付成功、已购买和恢复购买。
5. 商业拒绝不停止广播、不创建输出；播放中到期释放输出并回到等待。
6. 现有 `CastSessionCoordinatorTest`、窗口交接、安全和协议单测不回归。
7. `./scripts/gradlew-jdk17.sh testDebugUnitTest`
8. `./scripts/gradlew-jdk17.sh assembleDebug`
9. `node scripts/check-project-ready.mjs`
10. `node scripts/check-skills.mjs`
11. `git diff --check`

用户在 `S56_HQX` 上手测：试用等待页只显示剩余时间和报价广告、过期显示红色“无法投屏”及购买说明、Pro 显示皇冠和永久权益；无权益仍可发现、无权益不能产生画面 / 声音、试用和 Pro 可投屏、过期 / 撤权释放输出但不关闭窗口、支付 / 恢复后下一次投屏可用、设置标题角标与 03 歌词一致、关于二维码可解码、关闭窗口后 `7000 / 8200 / 1900` 全部释放，以及既有浮窗 / 全屏 / 行车保护回归。

## 8. 次生风险与取舍

暂无明确的确定性次生风险。最高优先级防守项是避免把商业拒绝误接到 `CastReceiverRuntime.stop()`；该不变量由 Router 单测、Service 逻辑和用户端端口手测共同验证。

本轮不做当前发送端自动重连，不做账号体系，不做动态价格缓存，不做后台唤起窗口，不做正式支付部署或生产发布。

## 9. 已落地事实与追踪

1. 已落地商业主链：`CommercialEntitlementCoordinator`、`CommercialRuntimeAccessGuard`、`CastCommercialAccessAdapter`、`CommercialController`、`CommercialSettingsRenderer`、`CastCommercialWaitingRenderer`，以及 Debug / Release 变体工厂。
2. 已落地运行门禁：`CastPlaybackRouter.beginSession`、`beginAirPlayMirrorSession`、`ensureSession` 和 `isCurrent` 均先检查当前已验证权益；拒绝时保留 receiver runtime，不创建媒体输出。
3. 已落地到期处理：清除授权、失效会话代次、释放媒体输出并回等待，不调用 receiver stop；`ACTION_SCREEN_ON` 和 `ACTION_TIME_CHANGED` 会触发重新验证。
4. 已落地测试：商业状态机、访问安全、签名协议、报价 / 支付 / 恢复 fixture、Debug / Release 隔离、布局契约和现有会话到期边界均有直接相关单测。
5. 当前客观边界：本轮 `lintDebug` 已通过；不执行 runtime smoke、截图、坐标点击或真实支付，目标车机交互按验证矩阵由用户手测。
6. 已补充等待页商业呈现：Trial / Expired 共用服务端报价长广告按钮并把辅助文案放在广告下方；Expired 直接替换主等待标题，Pro 使用缩小后的同源皇冠富文本并独立放在设置入口上方；等待页改为“中上部主状态锚点 + 偏下部权益 / 广告 / 设置锚点”的固定布局；设置标题角标改为 03 歌词同构布局。
