# Pearl 安卓手机独立钱包

基于 Pearl Research Labs 官方协议代码的原生 Android 钱包。Java 界面与 Android Keystore 负责交互和密钥保护；Go/JNI 使用固定版本的官方 Pearl 库派生地址、构建交易、签名，并逐个运行官方脚本引擎校验。它是独立开发项目，不是 Pearl Research Labs 官方发行的钱包。

## 安装与使用

当前版本 **0.3.1**。GitHub 安装包位于本仓库 **Releases → v0.3.1 → Assets**，文件名为 `pearl-wallet-android-0.3.1.apk`；本地构建文件保存在 `artifacts/`。最低 Android 8.0，支持 ARM64 手机与 x86_64 模拟器。此包关闭 Android 调试权限，使用独立本地开发证书签名；仍是未经独立安全审计的开发测试版本。

1. 在手机设置中启用锁屏 PIN 或密码，然后打开应用。
2. 选择「创建手机钱包」，验证手机解锁，设置至少 10 个字符的钱包密码。
3. 离线抄写 24 个英文助记词，完成随机三个词的备份验证。
4. 「接收」显示自己的完整主网地址和二维码，可复制或分享地址。
5. 「发送」输入 Pearl 主网收款地址和 PRL 金额，核对完整地址、金额与手续费，再验证手机解锁和钱包密码。本机签名后只上传签名交易。
6. 网络异常时，在设置的「查询或重发待确认交易」中查询，或重发同一笔交易。应用重启也会保留这笔交易；接收成功后等待链上确认。

恢复时选择「恢复已有手机钱包」，按编号填写 12、15、18、21 或 24 个 BIP39 英文助记词，也可点击「粘贴整段助记词」自动拆分。英文词库提示、拼写检查与 BIP39 校验均在本机进行，校验通过才能继续。随后核对恢复出的完整收款地址，验证手机解锁，设置新钱包密码；无需再次抄写或随机验证已导入的备份。取消或进入后台会关闭输入页并清除暂存内容。删除应用数据、重置手机或设备密钥失效后，需要助记词恢复；钱包密码无法替代助记词。此版本每台安装只保存一个钱包，不提供直接覆盖已有钱包的入口。

## 已实现功能

- 创建钱包、英文 BIP39 助记词恢复、离线备份与备份验证。
- 主网余额、未确认金额、最近 10 笔交易、浏览器完整历史。
- 收款地址校验、二维码、复制和分享。
- BigONE PRL/USDT WebSocket 实时推送、24 小时涨跌幅、24 小时 / 7 天触摸价格曲线；可选择 USDT、美元或人民币计价。
- 本地 UTXO 选择、整数 grain 金额、精确手续费与找零、转账审核、Schnorr 签名、官方脚本校验和 HTTPS 广播。
- 输入原始交易哈希、输出金额与归属校验；拒绝重复输入、未确认输入、未成熟 coinbase 和 dust。
- 广播前持久保存原签名交易，校验服务返回的交易 ID；超时不自动生成新交易。

生成的钱包使用无脚本树 Taproot（BIP86），固定路径 **`m/86'/808276'/0'/0/0`**，BIP39 附加口令为空。接收和找零使用同一个地址，方便恢复，但地址复用会降低隐私。可以向 Pearl 主网 `prl1p` Taproot 或 `prl1z` P2MR 地址付款。

**助记词备份适用于本应用的钱包。未实现 XMSS / P2MR 私钥签名，不应当把它用于恢复官方 Oyster 的 XMSS 账户，也不能声称具有 XMSS 抗量子保护。**

## 密钥与网络边界

助记词熵用 PBKDF2-HMAC-SHA256（600,000 次、随机盐）派生的钱包密码密钥进行 AES-256-GCM 加密，再由需要设备认证的 Android Keystore AES-GCM 密钥封装。支持时尝试 StrongBox。密文保存在应用私有 `noBackupFilesDir`，禁用云备份和设备数据迁移。

每次签名和查看备份均要求手机解锁验证与钱包密码，没有常驻的已解锁种子会话。敏感界面禁用截图、自动填充和个性化输入学习；进入后台时关闭敏感页面。字节与字符缓冲区在操作后尽力清除，但 Java/Go 字符串与运行时副本无法保证逐一擦除。

恢复流程在核对地址之后，短暂保留解码后的助记词熵以完成系统锁屏验证；仅在等待该验证时允许保留，最长 120 秒，取消、超时或销毁会清除。新密码在验证返回后输入。粘贴按钮仅在用户点击时读取剪贴板，建议手工填写纸质备份；第三方键盘或剪贴板服务可能读取输入，应用无法控制其他软件。

Keystore 保护的是加密密钥；Pearl 的 Schnorr 签名在应用进程中完成，签名期间种子与私钥会短暂进入内存。手机被控制或助记词泄露时，不能保证资金安全。

联网只传输公开地址、行情请求和签名交易；不传输助记词、种子、私钥或钱包密码。HTTPS/WSS 使用 Android 默认证书和主机名验证，禁用明文请求和跳转，仅允许官方 Blockbook、BigONE 和 CoinGecko 域名。公开行情订阅不包含钱包地址，也不需要交易所账户或 API 密钥。该版本依赖官方索引服务判断余额、确认和未花费状态，没有实现独立 SPV 验证；服务可看到查询的地址，也可影响可用性与建议费率。手续费会在签名前展示。

行情在应用前台即时连接，推送每次交易所 ticker 更新；后台断开，回到前台重新订阅。断线采用递增间隔重连，同时每 15 秒刷新 REST 行情；没有新的推送超过 45 秒也会刷新。页面明确显示实时推送、定时刷新、参考行情或过期状态，以及本机接收时间。交易所的成交价格在没有新成交时可能保持不变。

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

打包关闭调试权限的安装包：

```sh
./gradlew :app:assembleRelease
python3 scripts/package_apk.py --sdk /path/to/android-sdk
```

私有 APK 签名证书与密码保存在构建机器 `~/.pearl-wallet-build/signing`（目录权限 700，文件权限 600），不会打入 APK 或源码。保留该证书才能升级同一安装；正式发行需自行管理发布证书。

## 验证结果与限制

本地测试报告在 `artifacts/`，已发布版本的报告在 GitHub Release 附件中：Go 签名与对抗测试、Java 金额 / 地址 / API 单元测试、Android JNI / Keystore / 密码 / 超时重发测试、原生界面创建与恢复测试、模拟链节点转账测试。`simnet-transfer.json` 记录官方节点接收、出块确认和接收方未花费输出核验。0.3.1 已通过 11 项 Go 测试及 16 个子测试、21 项 Java 单元测试、7 项 Android 仪器测试；Android lint 为 0 项问题。

已验证 Android 15 模拟器及 Pearl 隔离模拟链，没有进行安卓真机测试、主网实币转账或独立安全审计。当前只支持固定单地址钱包；不支持导入 Oyster XMSS 账户、BIP39 附加口令、多账户、扫描付款二维码、手续费加速或硬件钱包。结果未确认的交易会阻止新发送，必须先查询或重发处理。

目录中 `pearl-wallet-preview.apk` 与旧观察模式截图是前一阶段的留档，不能用于证明此版签名能力。

## 数据与源码依据

- [官方 Pearl 仓库](https://github.com/pearl-research-labs/pearl)
- [源码与协议研究](docs/RESEARCH.md)
- [Pearl 官方 Blockbook](https://blockbook.pearlresearch.ai/api/v2/)
- [BigONE 公开 ticker 推送文档](https://open.bigone.com/docs/spot/pusher/)，交易对 `PRL-USDT`
- [CoinGecko Pearl](https://www.coingecko.com/en/coins/pearl-research)，API ID `pearl-2`，备用参考行情；USDT 汇率使用 `tether`

行情受网络和服务限流影响，失效时显示错误 / 过期状态，不填入虚构价格。

## GitHub 发布

发布说明见 [0.3.1 Release notes](docs/releases/v0.3.1.md)。仓库保存本应用完整 Java/Go/JNI 源码、测试、构建脚本和依赖版本；生成的 APK、原生库、SDK、缓存、本机路径和 APK 签名密钥不提交到 Git。

将已经验证的安装包、当前源码包、校验文件和测试报告保存在 `artifacts/` 后，可使用 GitHub CLI 登录并运行：

```sh
gh auth login --hostname github.com --git-protocol https --web
python3 scripts/publish_release.py --repo YOUR_ACCOUNT/pearl-wallet-android --dry-run
python3 scripts/publish_release.py --repo YOUR_ACCOUNT/pearl-wallet-android
```

脚本只向指定仓库推送当前 `main` 和 `v0.3.1` 标签，并创建开发测试版 Release；远端标签或同名附件不一致时停止，避免覆盖已经发布的版本。仓库需要先存在，且当前 GitHub 账号拥有写入权限。脚本不会重新生成签名证书。
