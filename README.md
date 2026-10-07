# Pearl Pocket · 掌珠钱包

Pearl Pocket（掌珠钱包）是基于 Pearl Research Labs 官方协议代码的原生 Android 手机独立钱包。Java 界面与 Android Keystore 负责交互和密钥保护；Go/JNI 使用固定版本的官方 Pearl 库派生地址、构建交易、签名，并逐个运行官方脚本引擎校验。它是独立开发项目，不是 Pearl Research Labs 官方发行的钱包。

## 安装与使用

当前版本 **0.10.0**。仓库公开，可在 **Releases → v0.10.0 → Assets** 下载 `pearl-wallet-android-0.10.0.apk`；本地构建文件保存在 `artifacts/`。最低 Android 8.0，支持 ARM64 手机与 x86_64 模拟器。此包关闭 Android 调试权限，使用独立本地开发证书签名；仍是未经独立安全审计的开发测试版本。

1. 在手机设置中启用锁屏 PIN 或密码，然后打开应用。
2. 选择「创建手机钱包」，验证手机解锁，设置至少 10 个字符的钱包密码。
3. 离线抄写 24 个英文助记词，完成随机三个词的备份验证。
4. 「接收」显示自己的完整主网地址和二维码，可复制或分享地址。
5. 「发送」输入 Pearl 主网收款地址和 PRL 金额，核对完整地址、金额与手续费，再验证手机解锁和钱包密码。本机签名后只上传签名交易。
6. 网络异常时，在设置的「查询或重发待确认交易」中查询，或重发同一笔交易。应用重启也会保留这笔交易；接收成功后等待链上确认。

恢复时选择「恢复已有手机钱包」，按编号填写 12、15、18、21 或 24 个 BIP39 英文助记词，也可点击「粘贴整段助记词」自动拆分。英文词库提示、拼写检查与 BIP39 校验均在本机进行，校验通过才能继续。随后核对恢复出的完整收款地址，验证手机解锁，设置新钱包密码；无需再次抄写或随机验证已导入的备份。取消或进入后台会关闭输入页并清除暂存内容。删除应用数据、重置手机或设备密钥失效后，需要助记词恢复；钱包密码无法替代助记词。通过首页顶部的钱包选择入口或设置中的「管理手机钱包」，可以创建、导入、重命名并切换最多 10 个独立钱包。每个钱包分别保管助记词与密码；不覆盖已有钱包。详见 [多钱包说明](docs/MULTI_WALLETS.md)。

## 已实现功能

- 多个独立钱包的创建、导入、命名与切换；英文 BIP39 助记词恢复、逐个离线备份与验证。
- Ethereum 主网 Aave V3 USDC 借贷流动性：每钱包 BIP44 地址、本机逐笔授权 / 存款 / 赎回签名、含利息仓位、浮动 APR、gas 预览与原签名交易恢复。详见 [DeFi 使用范围](docs/DEFI.md)。
- 主网余额、未确认金额、最近 10 笔交易、浏览器完整历史；余额隐藏与接收 / 发送筛选。
- 本机地址簿的新增、编辑、删除、名称 / 地址搜索与发送选择；完整主网地址校验。
- 纯地址收款二维码、复制和分享，可另行指定 PRL 收款金额；相机或本机图片扫码仅预填收款信息。
- BigONE PRL/USDT WebSocket 实时推送、24 小时涨跌幅、24 小时 / 7 天触摸价格曲线；可选择 USDT、美元或人民币计价。
- PRL/USDT 一次性目标价提醒，前台随报价检查，后台由 Android 约每 15 分钟调度；已触发提醒可重新启用。
- HeroMiners 公开矿池概览与收益情景计算，含矿池费、电费、租金、30/90 天净收益与盈亏平衡币价。
- 原生交易详情、确认数和交易 ID 复制。
- 本地 UTXO 选择、整数 grain 金额、精确手续费与找零、转账审核、Schnorr 签名、官方脚本校验和 HTTPS 广播。
- 输入原始交易哈希、输出金额与归属校验；拒绝重复输入、未确认输入、未成熟 coinbase 和 dust。
- 广播前持久保存原签名交易，校验服务返回的交易 ID；超时不自动生成新交易。

生成的钱包使用无脚本树 Taproot（BIP86），固定路径 **`m/86'/808276'/0'/0/0`**，BIP39 附加口令为空。接收和找零使用同一个地址，方便恢复，但地址复用会降低隐私。可以向 Pearl 主网 `prl1p` Taproot 或 `prl1z` P2MR 地址付款。

**助记词备份适用于本应用的钱包。未实现 XMSS / P2MR 私钥签名，不应当把它用于恢复官方 Oyster 的 XMSS 账户，也不能声称具有 XMSS 抗量子保护。**

## 新增工具的使用范围

带金额二维码使用 Pearl Pocket 自定义格式 `pearl:<address>?amount=<PRL>`，仅声明在本应用间兼容；默认纯地址二维码方便其他钱包使用。扫码不自动发送，未知参数或非主网地址会被拒绝。地址簿与提醒仅保存本机公开配置，卸载或清空应用数据会删除。

通知权限只在保存提醒时申请。提醒使用 BigONE PRL/USDT，新报价达到或超过/低于目标时通知一次。后台最低约 15 分钟检查，省电、断网、系统调度或强制停止会延迟，不能视为即时服务器推送；通知关闭时提醒保留、暂停触发。

挖矿页只查询 HeroMiners 公开统计，不在手机上挖矿。收益计算需填写预计费前 PRL 日产出、币价、功耗、电价、池费和租金；30/90 天假设输入不变，不预测实际产出。产品参考、数据单位与后续计划见 [产品研究](docs/PRODUCT_RESEARCH.md)。

## 原生界面预览

0.7.0 在 0.6.0 功能上增加跨链与价差工作台；0.6.0 增加深色模式、观察地址、完整历史与导出、个人矿工监控、发送全部余额、收款通知和桌面价格组件。保留统一原生布局、图标、表单与助记词编号网格。下面保留 0.8.0 的 Android 15 模拟器实际运行截图，使用公开的零熵测试向量钱包，不包含真实资产或私钥。

| 钱包首页 | 实时行情 |
| --- | --- |
| ![钱包首页](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-wallet.png) | ![实时行情](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-market.png) |
| ![深色钱包](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-wallet-dark.png) | ![深色行情](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-market-dark.png) |

[设计说明与下一步功能](docs/DESIGN.md)。余额隐藏同步遮盖无障碍朗读内容；发送审核仍显示实际金额。

## 密钥与网络边界

助记词熵用 PBKDF2-HMAC-SHA256（600,000 次、随机盐）派生的钱包密码密钥进行 AES-256-GCM 加密，再由需要设备认证的 Android Keystore AES-GCM 密钥封装。支持时尝试 StrongBox。密文保存在应用私有 `noBackupFilesDir`，禁用云备份和设备数据迁移。

每次签名和查看备份均要求手机解锁验证与钱包密码，没有常驻的已解锁种子会话。敏感界面禁用截图、自动填充和个性化输入学习；进入后台时关闭敏感页面。字节与字符缓冲区在操作后尽力清除，但 Java/Go 字符串与运行时副本无法保证逐一擦除。

恢复流程在核对地址之后，短暂保留解码后的助记词熵以完成系统锁屏验证；仅在等待该验证时允许保留，最长 120 秒，取消、超时或销毁会清除。新密码在验证返回后输入。粘贴按钮仅在用户点击时读取剪贴板，建议手工填写纸质备份；第三方键盘或剪贴板服务可能读取输入，应用无法控制其他软件。

Keystore 保护的是加密密钥；Pearl 的 Schnorr 签名和 DeFi 的 Ethereum ECDSA 签名在应用进程中完成，签名期间种子与私钥会短暂进入内存。手机被控制或助记词泄露时，不能保证资金安全。

联网只传输公开地址、行情请求和签名交易；不传输助记词、种子、私钥或钱包密码。HTTPS/WSS 使用 Android 默认证书和主机名验证，禁用明文请求和跳转，允许官方 Blockbook、BigONE、CoinGecko、HeroMiners Pearl 及新增只读桥 / Lighter / 固定 Ethereum RPC 服务。个人矿工查询会发送公开地址。公开行情订阅不包含钱包地址，也不需要交易所账户或 API 密钥。新增跨链与价差只读服务见 [跨链说明](docs/CROSS_CHAIN.md)；DeFi 的独立 RPC 边界、固定合约和签名限制见 [DeFi 说明](docs/DEFI.md)。该版本依赖官方索引服务判断余额、确认和未花费状态，没有实现独立 SPV 验证；服务可看到查询的地址，也可影响可用性与建议费率。手续费会在签名前展示。

行情在应用前台即时连接，推送每次交易所 ticker 更新；后台断开，回到前台重新订阅；用户配置价格提醒后，另由系统后台任务定期查询公开报价。断线采用递增间隔重连，同时每 15 秒刷新 REST 行情；没有新的推送超过 45 秒也会刷新。页面明确显示实时推送、定时刷新、参考行情或过期状态，以及本机接收时间。交易所的成交价格在没有新成交时可能保持不变。

美元与人民币使用 CoinGecko 的 USDT 汇率折算，未把 USDT 直接当作美元。汇率超过 30 分钟失效，获取失败时继续显示 USDT 报价。BigONE 历史曲线使用 5 分钟 / 1 小时收盘价，并更新末端报价；备用 CoinGecko 行情明确标注为参考数据。上次报价缓存只保存公开行情，过期时不能充当新行情。

## 构建与测试

在 Linux / WSL 下需要 JDK 17 或 21、Go **1.26.6**、Android SDK Platform 35 / Build Tools 35.0.0 / NDK **27.2.12479018**。Go 可通过 `PEARL_GO` 指定路径。设置 `ANDROID_SDK_ROOT`，或在 `local.properties` 配置 `sdk.dir`。

把官方 Pearl 源码放在工程的同级 `pearl-upstream`，并检出固定提交：

```sh
git clone https://github.com/pearl-research-labs/pearl ../pearl-upstream
git -C ../pearl-upstream checkout 2f8b770cac8f8b74be05c1fc51c2baeb76ce0701
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle 自动构建 ARM64 与 x86_64 Go/JNI 核心，并校验官方提交及签名相关源码未被修改。原生库采用 16 KB ELF 页对齐，APK 使用 16 KB ZIP 对齐。

```sh
cd core
go test -race -count=1 -v ./walletcore
```

官方节点应在其仓库目录内执行 `go build -buildvcs=false -o /tmp/pearld ./node`，然后在本工程 `core` 中执行 `PEARL_NODE=/tmp/pearld go run ./cmd/simnettest`。测试仅绑定本地 RPC、禁用 P2P 连接、临时生成模拟币，结束后清理节点。

模拟器测试使用专门的测试 PIN `24682468`，不可在用户真机运行：

```sh
adb shell locksettings set-pin 24682468
./gradlew :app:connectedDebugAndroidTest
python3 scripts/ui_smoke.py --adb /path/to/adb --reset-test-wallet
python3 scripts/check_live_api.py
# Optional public WSS check (requires websocket-client):
python3 scripts/check_live_market.py
```

UI 脚本会清空开发模拟器内的本应用数据，创建并恢复测试钱包；不会发送主网资金。助记词不会写入测试报告。

界面截图仅在明确运行 `DesignCaptureTest` 并传入 `capture_design=true` 时生成。该测试要求模拟器和已知公开测试向量钱包，由测试仪器临时允许公开页面浅色与深色截图，结束后恢复截图保护。发布 APK 不包含测试类，也没有取消截图保护的意图或偏好设置入口。

打包关闭调试权限的安装包：

```sh
./gradlew :app:assembleRelease
python3 scripts/package_apk.py --sdk /path/to/android-sdk
```

私有 APK 签名证书与密码保存在构建机器 `~/.pearl-wallet-build/signing`（目录权限 700，文件权限 600），不会打入 APK 或源码。保留该证书才能升级同一安装；正式发行需自行管理发布证书。

专用模拟器上可额外验证旧版有钱包时的覆盖升级：先构建并签名当前 APK，再执行 `python3 scripts/check_release_upgrade.py --reset-test-wallet --previous-apk artifacts/archive-0.6.0/pearl-wallet-android-0.6.0.apk`。脚本会删除模拟器中本应用的测试数据，在旧版创建公开恢复向量钱包，覆盖安装新版后验证地址、备份状态、设备验证和密码解密。`WalletUpgradeTest` 只在该显式流程下运行；常规仪器测试会跳过它。

## 验证结果与限制

本地测试报告在 `artifacts/`，已发布版本的报告在 GitHub Release 附件中：Go 签名与对抗测试、Java 金额 / 地址 / API 单元测试、Android JNI / Keystore / 密码 / 超时重发测试、原生界面创建与恢复测试、模拟链节点转账测试。`simnet-transfer.json` 记录官方节点接收、出块确认和接收方未花费输出核验。当前版本的准确测试数量与结果以 Release 附件 `TEST_REPORT.md` 和 `verification.json` 为准。

已验证 Android 15 模拟器及 Pearl 隔离模拟链，没有进行安卓真机测试、主网实币转账或独立安全审计。当前只支持固定单地址钱包；不支持导入 Oyster XMSS 账户、BIP39 附加口令、多账户、手续费加速或硬件钱包。相机扫码已实现，尚需真机验证相机兼容性。结果未确认的交易会阻止新发送，必须先查询或重发处理。

目录中 `pearl-wallet-preview.apk` 与旧观察模式截图是前一阶段的留档，不能用于证明此版签名能力。

## 数据与源码依据

- [官方 Pearl 仓库](https://github.com/pearl-research-labs/pearl)
- [源码与协议研究](docs/RESEARCH.md)
- [Pearl 官方 Blockbook](https://blockbook.pearlresearch.ai/api/v2/)
- [BigONE 公开 ticker 推送文档](https://open.bigone.com/docs/spot/pusher/)，交易对 `PRL-USDT`
- [CoinGecko Pearl](https://www.coingecko.com/en/coins/pearl-research)，API ID `pearl-2`，备用参考行情；USDT 汇率使用 `tether`

行情受网络和服务限流影响，失效时显示错误 / 过期状态，不填入虚构价格。

## GitHub 发布

发布说明见 [0.9.0 Release notes](docs/releases/v0.9.0.md)。仓库保存本应用完整 Java/Go/JNI 源码、测试、构建脚本和依赖版本；生成的 APK、原生库、SDK、缓存、本机路径和 APK 签名密钥不提交到 Git。

将已经验证的安装包、当前源码包、校验文件和测试报告保存在 `artifacts/` 后，可使用 GitHub CLI 登录并运行：

```sh
gh auth login --hostname github.com --git-protocol https --web
python3 scripts/publish_release.py --repo YOUR_ACCOUNT/pearl-pocket --dry-run
python3 scripts/publish_release.py --repo YOUR_ACCOUNT/pearl-pocket --expected-visibility public
```

脚本只向指定仓库推送当前 `main` 和对应版本标签，并创建开发测试版 Release；远端标签或同名附件不一致时停止，避免覆盖已经发布的版本。发布前会核对 APK、源码和验证报告的提交一致；指定 `--expected-visibility public` 时还会检查目标仓库确为公开。仓库需要先存在，且当前 GitHub 账号拥有写入权限。脚本不会重新生成签名证书。

## 0.6.0 新功能与使用范围

- 首页顶部“钱包名称 ▾ / 只读观察 ▾”打开选择入口；「管理手机钱包」选择签名钱包，观察地址列表选择只读地址。观察地址仅查询，发送入口会拒绝签名；不修改手机钱包身份。最多 20 个。
- 记录 → 完整交易历史：上一页 / 下一页，每页 25 笔；按交易 ID、对方地址、精确金额、本机备注或日期筛选当前页。支持备注、当前页 CSV，以及全历史 CSV（最多 5000 笔）。全历史导出检查分页数量和重复交易，变化时提示重试；公开服务不提供原子历史快照。
- 接收 → 指定金额 → 分享收款二维码图片。黑白 PNG 中包含完整地址与 PRL 金额；带金额请求格式适用于 Pearl Pocket。
- 发送 → 发送全部余额：内核计算手续费，使用成熟已确认 UTXO，单一输出且无找零。最多 100 个输入；仍需逐次设备验证和钱包密码。
- 挖矿 → 个人矿工监控：HeroMiners 普通模式，多个公开收款地址、工作器提交时间、可支付 / 待成熟 / 累计已支付、最近付款。当前与 24h 计分速率保留矿池原始单位，不换算为个人 H/s；超过 15 分钟未提交不等于已证明离线。尚未支持其他矿池与个人 Solo 模式。
- 行情：新增 30 天图；价格提醒可编辑和设置相对新报价的涨跌幅。每条触发一次，编辑重置状态；重新启用保留原基准。
- 设置：浅色 / 深色切换；可添加 Android 桌面 PRL/USDT 价格小组件。前台行情最多每分钟写入组件缓存，组件后台约 30 分钟刷新，可手动刷新；超过 5 分钟标为缓存，并显示报价接收时间。
- 设置 → 启用收款通知：首次查询静默建立基线，后续发现正净额收款和首次确认分别提醒。后台约 15 分钟查最新 100 笔，前台随地址刷新查最新记录；大量交易可能漏检，到账以完整历史为准。系统省电、网络、强制停止会影响检查。后台仅使用公开地址，不读取钱包密钥。

[应用方向：商家收款、矿工账本与团队看板](docs/APP_SCENARIOS.md)。上述新功能已经实现；应用方向文档明确区分现有流程和后续产品设计。

## 0.7.0 跨链与价差

行情 → 打开跨链与价差工作台。提供社区 PearlBridge PRL/WPRL 双向真实报价、额度和通道；PRL 转入使用原有手机钱包签名，WPRL 赎回使用外部 Ethereum 钱包。保存公开 Ethereum 地址，可查 WPRL/ETH 余额与桥进度。

接入 Lighter 固定 PRL 永续市场 4097 与 Uniswap V3 WPRL/USDT 固定单池的指定数量链上报价。可试算双向跨链现货成本、现货加永续空头的平仓和正负资金费情景；必须填写其它费用预算，USDC/USDT 为显式假设。暂停、错误资产或合约、地址变化、过期报价、盘口不足时拒绝继续。

跨链工具中的 Ethereum 和 Lighter 交易仍由外部钱包 / 账户签名，没有自动套利下单。钱包首页的 DeFi 模块另提供固定 Aave USDC 操作的本机 Ethereum 签名，使用同一加密助记词派生的不同 BIP44 私钥。桥是独立实验性项目；价差不是已实现利润，跨链耗时、强平和资金费变化会影响结果。

[完整流程、公式与协议研究](docs/CROSS_CHAIN.md)。

![原生跨链工作台](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-cross-chain.png)

## PearlFortune 与桌面算力组件

0.8.0 增加 **挖矿 → PearlFortune 我的矿工**：保存矿机使用的公开 PRL 收款地址，查看 1 / 8 / 24 小时估算算力、接受率和 worker 连接、自报算力、GPU 数量与更新时间。矿池估算和矿机自报分别显示，并保留各自的 H/s / MAC/s 单位。未知与不完整数据不会冒充零值。

在详情中点击 **添加算力与价格小组件**，或从系统桌面的小组件列表添加。支持矿池总览、个人矿工和多个独立配置，同时显示 BigONE PRL/USDT 及涨跌。点组件查看矿工详情，点「选择矿工」重新绑定。后台约每 15 分钟尝试更新，执行时间由安卓系统控制；离线和刷新失败保留带标记的缓存。

「查看 PearlFortune 完整页面」会直接打开已填入相同公开地址的 [官网矿工页面](https://pearlfortune.org/#miner)，保留完整网页的结算、账本、收益计算及 NOCK 合并挖矿查看。所有操作仅查询公开数据，不改变钱包签名地址，不执行挖矿或提现。详见 [组件说明与数据边界](docs/MINING_WIDGETS.md)。

| PearlFortune 原生页面 | 安卓桌面组件 |
|---|---|
| ![PearlFortune](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-fortune.png) | ![算力与价格组件](https://github.com/EthanHash-ux/pearl-pocket/releases/download/v0.8.0/ui-mining-widget.png) |

## 0.9.0：独立多钱包与钱包优先路线

同一台手机最多保存 10 个独立手机钱包，支持创建、导入、重命名、切换，并拒绝重复导入。原有钱包原地保留，升级无需重新加密；每个新增钱包使用独立设备密钥与密码保护。转账确认展示付款钱包和完整地址；待发送的原签名交易按钱包隔离，切换不转移资金。收款通知启用后检查所有可读取的钱包与观察地址。

新建、导入或切换不会覆盖已有钱包。每个钱包分别验证备份；新建钱包未完成备份前不能发送。更换手机需逐个使用助记词恢复，钱包名称与公开配置不能代替备份。完整边界见 [多钱包说明](docs/MULTI_WALLETS.md)。

产品主线为安全持有、收取、支付与记录 Pearl 资产。下一阶段优先收款与交易对账、换机恢复、资产与收支报表。矿池查询和跨链报价作为辅助工具保留，未新增自动交易或挖矿执行。见 [钱包优先路线](docs/APP_SCENARIOS.md)。
