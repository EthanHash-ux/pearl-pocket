# Pearl 钱包接入研究

研究日期：2026-10-07。源码基准提交：`2f8b770cac8f8b74be05c1fc51c2baeb76ce0701`。

## 已核实的协议与接口

Pearl 是独立 L1、UTXO 网络，钱包服务名为 Oyster。官方仓库包含 node、wallet、spv、XMSS 与桌面钱包，不属于 EVM 代币。

主网地址 HRP 为 `prl`，测试网为 `tprl`。Taproot 使用见证 v1、32 字节公钥程序和 Bech32m。主网 HD coin type 是 `808276`；BIP86 路径作用域为 `m/86'/808276'`。金额最小单位 grain 为 10^-8 PRL。

来源：

- [网络参数](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/node/chaincfg/params.go)
- [地址校验库](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/apps/packages/pearl-address-validation/src/index.ts)
- [金额定义](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/node/btcutil/amount.go)

Oyster 提供 JSON-RPC：`getbalance`、`getnewaddress`、`validateaddress`、`listtransactions`、`chainsynced`、`walletpassphrase`、`walletlock`、`sendmany`。Pearl 的 `sendmany` 增加必需手续费率参数，单位 **PRL/KB**，不能套用未核实的 Bitcoin 客户端参数顺序。

官方桌面钱包当前使用 `sendmany ['default', {address: amount}, feeRate, minconf]`。主网 Oyster RPC 端口见 README 为 44207，代码中的桌面端口另有配置，接入时应以用户实际服务配置为准。

- [官方 RPC 客户端](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/apps/apps/pearl-desktop-wallet/src/main/services/wallet-service/wallet-rpc-methods.ts)
- [发送方法定义](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/node/btcjson/walletsvrcmds.go)

## 原生钱包兼容性

Pearl 交易 ID 与 Taproot 签名算法的源码采用 SHA256 / 双 SHA256 和 BIP341 的 tagged hash。手机签名已使用 Pearl 官方 `txscript` 引擎逐个校验，并在隔离 simnet 官方节点完成接收、出块确认与接收方未花费输出核验。

当前官方钱包会按配置在 Taproot 中加入由另一 HD 作用域派生的 XMSS tapscript。带该脚本树的输出公钥与无脚本树的 BIP86 输出公钥不同。因此仅相同派生路径或同一助记词不能证明与 Oyster 地址 / 恢复行为相同。

XMSS 具有状态：同一个密钥的消息编号不可复用。其源码注明复用会使攻击者能够伪造签名。若实现此能力，需设计断电、恢复、并发设备下的持久化和编号安全，不能直接复制“每次从 0 开始”的签名流程。

- [Taproot 签名摘要](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/node/txscript/sighash.go)
- [脚本校验](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/node/txscript/standard.go)
- [官方地址派生与 XMSS 脚本树](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/wallet/waddrmgr/scoped_manager.go)
- [XMSS 签名状态要求](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/xmss/xmss.go)

## 公共数据源

官方桌面钱包的手续费查询采用 `blockbook.pearlresearch.ai`。已实测该服务 HTTPS `/api/v2/` 返回 coin=Pearl、chain=mainnet、decimals=8、同步状态和链高度。

读取接口：

- `/api/v2/address/{address}?details=txs&page=1&pageSize=10`：grain 整数字符串余额、未确认余额、交易。
- `/api/v2/utxo/{address}?confirmed=true`：未花费输出。
- `/api/v2/estimatefee/6`：估计手续费率。

交易列表金额需要计算属于当前地址的输出总和减输入总和，避免把找零当成新收入。发送净额会包含支付的手续费，应在未来详情页区分收款金额与手续费。

CoinGecko 页面链接虽然是 `pearl-research`，页面注明 API ID 为 `pearl-2`。已实测 `simple/price?ids=pearl-2&vs_currencies=usd,cny&include_24hr_change=true&include_last_updated_at=true` 与 `coins/pearl-2/market_chart` 返回有效数据。ID `pearl-research` 的简单价格接口返回空对象。

手机独立钱包已实现本地签名与广播流程。模拟链完整转账已验证；主网仅测试公开数据及空请求接口响应，没有进行主网实币转账。

## 本次手机独立钱包实现

用户已选择手机独立钱包。采用 BIP39 英文助记词，以空附加口令生成种子；使用官方 `hdkeychain.DeriveNonStandard` 派生 `m/86'/808276'/0'/0/0`，再以 `ComputeTaprootKeyNoScript` 生成固定 Taproot 地址。

通过原始前置交易计算输出归属与金额，生成完整 `MultiPrevOutFetcher` / `TxSigHashes`，用 `TaprootWitnessSignature` 签名，每个输入经 `NewEngine(...).Execute()` 校验后才返回签名交易。主网手续费接口给出 PRL/KB，精确转换为 grain/1000 virtual bytes。

广播使用 HTTPS POST `/api/v2/sendtx/`，请求体是原始交易十六进制文本。方法依据 [Blockbook 官方服务实现](https://github.com/trezor/blockbook/blob/master/server/public.go)；原签名交易在广播前持久保存，异常后仅查询或重发相同字节。

本实现不生成 XMSS/P2MR 账户，但支持向主网 P2MR 地址支付。不能把它的 BIP86 备份恢复范围解释为完整 Oyster 恢复兼容性。详见工程 README 与测试报告。

## 0.3.0 实时行情与恢复交互

CoinGecko 的简单价格存在缓存，轮询不能提供逐笔推送。[CoinGecko Pearl 页面](https://www.coingecko.com/en/coins/pearl-research)列出的 BigONE Pearl 市场经实际 REST 和 WSS 核验，确实使用 `PRL-USDT`。公开连接 `wss://api.big.one/ws/v2`，协议头 `Sec-WebSocket-Protocol: json`，发送 `subscribeMarketsTickerRequest`，先收到 `tickersSnapshot`，之后收到 `tickerUpdate`。协议依据 [BigONE 官方推送文档](https://open.bigone.com/docs/spot/pusher/)；不需要登录或 API 密钥。

应用计算 `(close-open)/open*100` 得到 24 小时变化，不把 REST 的绝对差值 `daily_change` 当成百分比。推送未提供行情事件时间时仅标注本机接收时间。主报价单位 USDT；法币显示依据 CoinGecko `tether` 的 USD/CNY 汇率折算，汇率失效时回到 USDT。断线重连与 REST 回退独立于余额和转账流程。

恢复输入使用本地 2048 个英文 BIP39 词库，按 12/24 格编号填写，整段粘贴自动拆分并检查拼写和校验和。校验通过后仍由官方 Go 核心导入和派生地址，再给用户核对完整地址。新钱包密码在系统认证之后输入；恢复不再重复展示抄写备份页面。原有派生路径、金库格式与签名流程保持一致。


## 0.3.1 BIP39 导入标准核验

再次联网核实 [Pearl 官方钱包导入源码](https://github.com/pearl-research-labs/pearl/blob/2f8b770cac8f8b74be05c1fc51c2baeb76ce0701/wallet/walletsetup.go)：Oyster 使用 `bip39.IsMnemonicValid` 校验，再以 `bip39.NewSeed(mnemonic, "")` 生成种子。钱包文件加密密码与 BIP39 附加口令不同。官方导入还支持旧版十六进制种子，本应用没有实现这一入口。

[BIP39 标准](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)规定 128/160/192/224/256 位熵对应 12/15/18/21/24 个词。安卓 0.3.1 补齐五种词数的选择、整段粘贴自动识别、离线校验、Go/JNI 导入和签名；新建钱包仍生成 24 词。英文词库与空 BIP39 附加口令与上述官方导入方式一致。

BIP39 规定助记词与种子转换；BIP32 规定分层派生；BIP86 规定本应用使用的 Taproot 地址形式。支持标准助记词并不表示会扫描 Oyster 的所有地址和账户。本应用仍只恢复 `m/86'/808276'/0'/0/0` 无 XMSS 脚本树的地址，未实现其他派生索引、账户扫描或 XMSS 状态恢复，导入时须核对完整地址。
