# PRL / USDC 原生现货（0.11.0）

钱包首页 → **买卖 PRL**。接入 `pearl-trade.com` 的原生 PRL / Arbitrum USDC 订单簿，可以在本机充值 PRL、签署买单或卖单、撤单、申请提现，并核验 USDC 链上收款。

这是开发测试功能。已核对官网接口、独立签名实现、Android 模拟流程及公开历史提现；**尚未用主网实币验证订单提交、充值入账和提现处理**。平台接口不是本项目控制的服务，没有可用性或到账时间保证。

## 卖出并收到 USDC

1. 为所选钱包生成 EVM 地址。与 DeFi 使用相同的标准 `m/44'/60'/0'/0/0` 路径，网络是 **Arbitrum One，42161**。Pearl 私钥仍使用原有独立路径。
2. 获取与该 EVM 账户绑定的平台 Pearl 充值地址。在官网用同一账户核对完整地址，勾选确认，输入 PRL 充值数量。首次地址来自平台 API，不能在本机独立推导；本机固定首次记录，后续变化会拒绝充值。
3. 审核真实 Pearl UTXO、金额、手续费和收款地址，验证手机解锁和钱包密码。本机 Schnorr 签名、逐输入脚本验证、广播前保存原交易。签名前再查地址绑定和市场配置，核对超过两分钟拒绝签名。
4. 等待平台记账。官网目前说明 PRL 充值等待六个 Pearl 区块确认；已广播、已确认与平台可交易余额分开，不能因暂未记账而重复充值。
5. 选择卖出数量和**最低接受价**，查看当前真实深度、平台费率和成交情景。订单使用限价，签名绑定市场、方向、数量和价格；满足保护价的部分可以立即成交，剩余部分留在订单簿。不会以最后一档外推不足的深度，也不保证全部成交。
6. 再次验证手机与钱包密码，本机签署平台 EIP-712 请求。成交所得 USDC 先在平台账本内。
7. 输入提现总扣款金额，核对平台费、预计净额及自己的固定 Arbitrum 收款地址，另行验证并签名。提现需平台运营方处理。
8. 在「提现与到账查询」核验交易：固定 USDC 合约的 Transfer 日志、本钱包净收款、回执成功、交易和区块哈希、规范区块、请求时间以及 `safe` / `finalized` 状态。平台报告 `confirmed` 本身不作为到账证明。

**充值、订单接收、部分成交、全部成交、提现申请、L2 包含、L1 safe、L1 finalized 是不同阶段。** 此路线不是跨链原子兑换；平台保管交易期间的 PRL 与 USDC，运营与提现权限会影响用户能否退出。

## 买入、退出与范围

买单使用已有平台 USDC 余额，绑定最高买入价，买入费从收到的 PRL 扣除；可以买后申请 PRL 提现到所选钱包。每次提现固定为该钱包的 Pearl 或 EVM 收款地址，本机不签署向第三方的提现。

当前版本没有内置 Arbitrum USDC 授权 / 充值、普通 ETH / USDC 转出、WalletConnect、真市价单、自动成交后提现、定时卖币或后台交易执行。USDC 充值及普通 Arbitrum 转账需要兼容外部钱包。Ethereum 主网 Aave USDC 与 Arbitrum USDC 分开，不能直接相互充值；没有接入同名 PRL 永续市场或 WPRL 桥兑换。

最少 1 PRL，数量按 0.01 PRL，价格按 0.0001 USDC，精度分别为 8 / 6 位，全部使用整数和精确十进制；不自动截断数量。当前本机固定交易单位，每次读取市场时核对；平台修改市场单位后暂停执行并要求更新。本版需要两侧有效盘口才能预览新订单。

费率从 `/v1/markets` 的 **PRL 市场、买卖方向**分别读取，不采用博客历史固定百分比。普通账户费率用于保守估算，用户优惠可能使实际费用更低。下单前重查费率和可用余额；签名格式不绑定费率，最终处理仍遵循平台规则。多笔成交可能产生不同的逐笔费用取整，净额是估算。

提现请求中的 `amount_units` 为总扣款，提现记录中的 **`amount_units` 为链上净额**，另有 `fee_units`。本机校验「返回净额 + 返回费用 = 已签署扣款」，再将链上实际收到的 USDC 与净额核对。公开历史提现与活动接口、链上日志已交叉验证该口径。

## 请求保存与恢复

每钱包单独保存账户、充值地址、nonce、本机操作编号与最多 100 条记录。EIP-712 只允许三种结构化操作：PRL 保护限价单、按编号撤单、PRL / USDC-ARB 提现。原生层生成 JSON payload，不接受网页、服务器提供的任意待签名消息或摘要；不允许无限授权、任意 dApp、未知资产或外部提现收款人。

签名之前要求已备份钱包、手机锁屏认证和新输入的钱包密码；从实际解密种子派生签署账户并独立核对。请求与签名在网络提交前写入 `noBackupFilesDir`，写入失败不提交。网络操作排在密码 / 熵缓冲区清理之后，不建立常驻解锁会话。

超时、响应格式不匹配或无法确认提现编号时保留请求，阻止下一笔签署。**不自动重发或换新 nonce**。公开接口不能按签名 nonce 精确反查结果，本项目没有依赖未经确认的幂等保证。用户先查询平台订单 / 提现或官网记录，再明确核对并解除本机锁定；此动作不撤单、不撤回提现，也不删除平台记录。恢复出的同一助记词仍控制同一平台账户，但卸载应用会丢失本机恢复记录与地址固定记忆。

## 网络与来源

- 固定 REST 主机 `https://api.pearl-trade.com`：仅支持所需的市场、盘口、余额、用户地址、订单和提现读取；账户地址分配、受校验的订单 / 撤单 / 提现写入。不存在通用 URL、API 密钥或任意脚本接口。
- 官网 EIP-712 schema 为 `Pearl OTC` / `1`，`Request(action,payloadHash,nonce,issuedAt)`；**此上游 domain 不包含 chainId 或 verifyingContract**。本应用固定消息类型、字段与提交主机，但不能替平台增加链级 domain 隔离。签名只适用于该托管账本，不能当作链上交易哈希。
- 固定只读 RPC `https://arb1.arbitrum.io/rpc`：chain ID、区块、固定 USDC 余额、交易回执；拒绝签名、广播和任意合约调用。`safe` 表示数据已发布到 L1，`finalized` 对应父链最终确认，均与 L2 包含分开显示。链上信息仍依赖 RPC，本机没有独立 rollup / L1 验证。
- Arbitrum 原生 USDC：`0xaf88d065e77c8cc2239327c5edb3a432268e5831`，不接收 USDC.e。市场配置的 Arbitrum vault 固定为 `0x779fb955d033dc12e8b08a07d69f4c1973aa0c37`；本版本不签署 vault 授权或充值。
- HTTPS 使用系统证书与主机名验证，拒绝重定向和明文。联网只传公共账户与签名，不传助记词、熵、私钥或钱包密码；平台及 RPC 能看到查询地址。

依据：[官方买卖指南](https://pearl-trade.com/blog/how-to-buy-and-sell-prl/)、[官网签名与 REST 实现](https://pearl-trade.com/web3.js)、[订单簿托管标记](https://pearl-trade.com/trade.jsx)、[充值和提现界面](https://pearl-trade.com/deposit.jsx)、[公开市场配置](https://api.pearl-trade.com/v1/markets)、[充值说明](https://api.pearl-trade.com/v1/deposit-instructions)、[Circle Arbitrum USDC 合约](https://www.circle.com/blog/usdc-on-arbitrum-now-available)、[Arbitrum RPC 与 chain ID](https://docs.arbitrum.io/chain-info)、[Arbitrum 区块最终状态](https://docs.arbitrum.io/build-decentralized-apps/troubleshooting-building)。研究与接口核对时间：2026-10-08。
