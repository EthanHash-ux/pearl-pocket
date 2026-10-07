# 跨链与价差工作台（0.7.0）

研究与接口验证：2026-10-08，北京时间。以下是已经实现的交互和范围，不是收益承诺。

## 已实现

行情 → **打开跨链与价差工作台**。无需交易所 API 密钥，可以查社区 PearlBridge 状态与费用、Ethereum WPRL/ETH 余额、Uniswap 指定数量报价、Lighter PRL 永续盘口，试算现货跨链路线和现货＋永续对冲。

| 功能 | 执行方式 |
| --- | --- |
| PRL → WPRL | 本机读取桥报价及与 Ethereum 收款人绑定的 Pearl 充值地址；用户核对网站完整地址并勾选确认后，进入已有 Pearl 本机规划、审核、设备验证、密码与签名流程 |
| WPRL → PRL | 本机读取真实赎回报价、核对固定 Ethereum 合约；复制本机 Pearl 收款地址，在 PearlBridge 网站使用外部 Ethereum 钱包授权与赎回 |
| WPRL / ETH 余额 | 保存单个公开 Ethereum 地址，核对 EIP-55，查询同一主网区块的 WPRL 和 ETH；遵循隐藏金额偏好 |
| 兑换 / 交易 | 可打开 PearlBridge、Uniswap 池和 Lighter PRL 页面；Ethereum 与交易所签名由外部钱包 / 账户完成 |
| 跨链进度 | 转入前保存 Pearl 交易 ID；可另填 Ethereum 赎回哈希；查询桥生命周期和目标链交易。桥报告的完成状态仍须在目标链核对 |
| 现货成本 | 当前数量的 BigONE 深度、真实桥费 / 净额、同一 Ethereum 区块的 Uniswap Quoter 报价；双向成本试算 |
| 永续对冲 | BigONE 现货买入与 Lighter 买盘上的空头成交深度、平仓现货价格 / 价差、持仓时间、正负资金费、账户费率、成本和 USDC/USDT 汇率假设 |

**没有 Ethereum 私钥导入、BIP44 私钥派生、内置 WalletConnect 签名、内置 Lighter 开平仓、自动交易、自动跨链或后台套利执行。** 现有 Pearl 助记词和派生路径不会用于网页或 Ethereum 签名。外部签名流程不等于本应用已完成赎回或交易。

## 固定资产与协议

- Pearl 原生 PRL：Pearl 主网、8 位 grain，本机签名逻辑保留。
- PearlBridge WPRL：Ethereum 主网 chain ID **1**，**8** 位精度，固定合约 **`0x07696dcab55e62cfef953666b29fe1970518cb00`**。
- 桥控制器：**`0xa6571b73489d4ebfa269a107208665df7c80aef5`**。
- Uniswap V3 WPRL / USDT：固定池 **`0x89a67c6dee35db9815da2fb9191f0998a8b37c39`**，fee tier **10000（1%）**。USDT 固定 Ethereum 合约 `0xdac17f958d2ee523a2206206994597c13d831ec7`、6 位精度。每次路线报价核对主网、代币精度和 Factory `getPool` 结果。仅支持此已核对单池，没有声称找到全市场最优路由。
- Uniswap QuoterV2：`0x61ffe014ba17989e743c5f6cb21bf9697530b21e`，使用只读 `eth_call`。卖出使用 exact input，买入指定 WPRL 数量使用 exact output；拒绝无法完整报价及价格边界的部分输入兑换。
- Lighter PRL：固定市场 **4097**，响应必须仍为 PRL、perp、active、未冻结、未处于只减仓。展示 USDC 口径、标记价、指数价、买卖盘与最近已结算小时资金费。不会按其他交易所相同的 PRL 符号挑选或合并资产。

PearlBridge 是独立的社区实验性桥，不是 Pearl Research Labs 的官方担保。PRL 保管、签名验证者、运营与可升级合约会引入额外信任；暂停、额度和应急机制可能影响铸造或赎回。API 状态与 RPC 结果没有经过本机独立链验证。WPRL 不能直接按 PRL 转入 Lighter，也没有实现 WPRL 作为其保证金。

## 桥转入的核对与恢复

1. 输入 PRL 数量和自己控制的外部 Ethereum 地址；大小写混合地址须通过 [EIP-55](https://eips.ethereum.org/EIPS/eip-55)，全小写 / 全大写地址生成规范校验显示。
2. 从桥的公开 `/v1/status`、`/quote/mint`、`/deposit-address` 读取状态和绑定关系。金额全程整数 grain。报价必须明确包含暂停、额度、通道、延迟和确认数；缺失、未知、金额不守恒、旧报价或错误合约会被拒绝。
3. 第一次得到的 ETH → Pearl 充值地址在本机持久固定。后续变化会阻止发送。**这是首次记录固定，并不是独立推导或桥可信的证明**：当前 API 不提供可在本机验证的派生 xpub；首次查询被控制时仍有风险。每次都要求用户在 pearlbridge.xyz 用同一 Ethereum 地址核对完整充值地址与报价。
4. 本机 PRL 规划前，以及用户点击“验证并发送”之后、设备认证之前，重新核对桥地址、费用、净额、通道与确认数。变化、暂停或额度不足时取消。核对后两分钟内须完成签名；网络查询发生在解密种子之前。桥状态仍可能在查询之后改变，因此不能保证即时到账。
5. 原有整数手续费、成熟 UTXO、原始交易归属校验、签名、官方脚本引擎与广播前持久保存继续使用。跨链公开记录也在广播前保存。存储失败不广播。
6. 广播超时保留相同原交易；已有待确认交易阻止新发送。先处理原交易，再查桥进度。记录存在、Pearl 转账确认或桥 API 报告成功均不是独立的 WPRL 到账证明。

当前 API 现场核对示例：100 PRL 转入净得 96 WPRL，桥费 4 PRL，至少 6 个 Pearl 确认；慢通道额外等待 86400 秒。这是当时的公开报价，**不会固定进计算公式**。每日额度、费用和政策以后可能变化。

## 测算口径

**PRL 买入 → 桥 → WPRL 卖出**：为得到转入数量 Q，BigONE 深度按 Q / (1 − 用户现货费率) 预留买入币手续费，再卖出桥实际净额的 WPRL。DEX 收款扣除额外价格变化预留和用户填写的其它成本。

**WPRL 买入 → 赎回 → PRL 卖出**：DEX exact output 算购买 Q WPRL 所需 USDT；桥赎回净额在 BigONE 买盘逐档卖出，扣现货交易费，再减去买入价格变化预留与其它成本。

DEX 当前报价已经包含该池的 1% 手续费和池内价格影响，不重复扣池费。盘口不足时不按最后一档外推。服务分别先后取样，读取耗时超过 30 秒拒绝结果，页面另标记快照超过 30 秒；不能把当前报价锁定到跨链完成。gas、授权、提币和 Pearl 网络费必须由用户填写 USDT 预算，**预算、账户实际费率、充值提现可用性与两端库存未自动验证**。费用扣在币还是报价币可能因实际交易所规则不同，本应用用上述保守口径；最终执行前核对。

**现货＋永续**：入场金额差不等于已实现收益。情景净额包含假设平仓时的现货销售与空头盈亏、双边账户手续费、每小时固定资金费率 × 当前指数名义金额 × 小时数、其它成本与额外预留。资金费正数代表空头收入，负数代表空头支出；USDC/USDT 是显式可修改假设，不默默视为等值。实际资金费、汇率、价差、保证金和价格均会变化。指数乘市场初始保证金比例仅用于最低初始估算，不是抵抗强平的安全缓冲。需要分别准备现货和 USDC，不存在通过桥自动成交的原子套利。

## 网络边界

新增 GET 主机仅为 `api.pearlbridge.xyz` 和 `mainnet.zklighter.elliot.ai`。原 Pearl 广播 POST 仍只允许官方 Blockbook 的固定 `sendtx/`。

Ethereum RPC POST 限制到 `https://ethereum-rpc.publicnode.com`，允许的只有 `eth_chainId`、`eth_blockNumber`、`eth_getBalance` 与固定代币 / Factory / Quoter 的明确 `eth_call`。请求方法、参数、合约及 ABI 输入均受限制；拒绝 `eth_sendRawTransaction`、签名、approve、转账、任意合约和跳转。没有降低 TLS 或证书验证。网页使用系统浏览器，不内嵌可访问钱包密钥的脚本桥。

本机保存公开 Ethereum 地址、充值地址固定记录及最多 100 条桥交易 ID；不保存交易所密钥、Ethereum 种子或自动授权。卸载数据会删除这些公开记录及固定地址记忆。

## 依据

- [PearlBridge 公共 API 索引](https://api.pearlbridge.xyz/v1)、[开发文档](https://pearlbridge.xyz/developers)、[桥工作原理](https://pearlbridge.xyz/infrastructure)。
- [桥维护方钱包 API 实现](https://github.com/PearlBridgeXYZ/pearlwallet/blob/main/src/services/bridge-v1.ts)：8 位金额、真实生命周期及首次充值地址记录的信任边界。
- [BigONE 发布 WPRL / PRL 的原始公告](https://bigone.zendesk.com/hc/en-us/articles/59478678240409-BigONE-Lists-PearlBridge-Bridged-WPRL-Ethereum-WPRL-PRL-Trading-Pair-is-Now-Available)：桥网站与 token 合约；该 WPRL / PRL 交易对不用于 USDT 直接价差，避免单位错配。
- [Lighter 盘口 API](https://apidocs.lighter.xyz/reference/orderbookorders)、[市场元数据](https://apidocs.lighter.xyz/reference/orderbookdetails)、[PRL 实时元数据](https://mainnet.zklighter.elliot.ai/api/v1/orderBookDetails?market_id=4097)。
- [Lighter 资金费](https://docs.lighter.xyz/trading/funding)、[已结算小时数据接口](https://apidocs.lighter.xyz/reference/fundings)、[USDC 盈亏口径](https://docs.lighter.xyz/trading/pnl-and-total-account-value)、[账户收费差异](https://docs.lighter.xyz/trading/trading-fees)。
- [Uniswap Ethereum 合约部署](https://developers.uniswap.org/docs/protocols/v3/deployments/v3-ethereum-deployments)、[官方 QuoterV2 ABI](https://github.com/Uniswap/v3-periphery/blob/main/contracts/interfaces/IQuoterV2.sol)。池地址来自桥网站当前公开配置，并通过主网 Factory 调用核对。

开发测试版本：模拟器、只读主网 API 和隔离模拟链验证；没有主网实币桥接、合约授权、实单套利、真机验证或独立安全审计。界面中的可执行 PRL 转入流程仍须用户逐次核对和授权。
