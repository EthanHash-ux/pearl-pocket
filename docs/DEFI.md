# DeFi 借贷流动性

0.10.0 在钱包首页提供 **打开 DeFi**，支持 Ethereum 主网 Aave V3 的 USDC 存款、仓位查询和无借款仓位的赎回。这是向借贷池提供单资产流动性；不包含 Uniswap 双币或集中流动性 LP、借款、杠杆、闪电贷或收益聚合。

## 使用

1. 选择本机手机钱包，完成该钱包的助记词备份验证。
2. 打开 DeFi，验证手机锁屏和钱包密码，生成 Ethereum 地址。它从当前钱包的同一份 BIP39 助记词派生，路径 `m/44'/60'/0'/0/0`，附加口令为空；与 Pearl 的 `m/86'/808276'/0'/0/0` 使用不同私钥。原有 Pearl 地址和密文无需迁移。
3. 向显示的 **Ethereum 主网地址**转入 USDC 和用于 gas 的 ETH。每个独立钱包有自己的 Ethereum 地址和待确认交易记录。观察 Pearl 地址无法签署 DeFi 交易。
4. 查看 USDC 可用余额、ETH 手续费余额、aUSDC 当前存款余额和浮动存款 APR。aUSDC 余额包含协议已经计入的利息；本版未单独计算投入本金或净收益。APR 不是固定收益或复利 APY。
5. 输入明确的 USDC 金额，最多 6 位小数。授权不足时先预览授权交易，显示 Aave Pool 授权对象、金额和最高 ETH 手续费。只授权输入金额，不使用无限额度。逐次通过手机验证和钱包密码签名。
6. 查询授权的链上结果，确认后再次选择存款并核对预览。授权本身不会存入 USDC，也不自动签署下一笔交易。存款按协议规则可能启用为抵押品。
7. 赎回指定 USDC 金额到当前钱包的 Ethereum 地址。超过存款余额、池内流动性不足或该地址存在借款时阻止操作；有借款的仓位需到 Aave 管理抵押和借款。

## 固定市场与验证

| 对象 | Ethereum 主网地址 |
| --- | --- |
| Chain ID | 1 |
| PoolAddressesProvider | `0x2f39d218133AFaB8F2B819B1066c7E434Ad94E9e` |
| Aave V3 Pool | `0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2` |
| USDC，6 位精度 | `0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48` |
| aUSDC，6 位精度 | `0x98C23E9d8f34FEFb1B7BD6a91B7FF122F4e16F5c` |

每次仓位查询核对 chain ID、Provider 的 Pool、Pool 的 Provider、储备中的 aToken 地址和两种代币精度；全部合约读取固定在同一个区块。存款前检查启用、暂停、冻结、供应上限、余额和授权。调用 `eth_estimateGas` 模拟目标操作，失败不进入签名预览。区块时间超过 180 秒拒绝预览。

EIP-1559 最高 gas 单价使用区块 base fee × 2 + 节点优先费；gas limit 使用模拟量 × 1.2 + 10000。预览显示 `gas limit × maxFeePerGas` 的最高 ETH 费用，实际执行可能更低，执行失败也可能收取网络费用。上限为 1000000 gas、500 gwei，预览有效 5 分钟。未提供费用自定义、加速、取消或替换交易。Ethereum 本机签名目前只支持此借贷流程，尚无普通 ETH / USDC 发送入口；这类转账需要在兼容 BIP44 钱包恢复同一助记词后管理。

Go 本机签名只接受 chain ID 1 上固定 USDC 的 `approve(Pool,amount)`、`supply(USDC,amount,自身,0)` 和 `withdraw(USDC,amount,自身)`。不接受任意合约调用、任意收款人、附加 ETH 转账、非空 access list 或无限额度。RLP 使用未修改的 go-ethereum v1.17.7 库，Keccak 和 secp256k1 签名在本机执行。每笔签名后恢复签名地址、核对 calldata，再返回公开交易。

## 网络与恢复

`EvmPublicApi` 保持跨链工具原有的只读限制。DeFi 使用单独的 `DeFiApi` / `httpsDeFi` 边界，固定 HTTPS RPC 主机，限制查询合约、参数、模拟交易和可广播的签名操作。密码、助记词、种子和私钥不会进入网络层；解密缓冲区清除后才将公开签名结果交给提交流程。没有内嵌可调用密钥的网页或通用 dApp 签名接口。

每个钱包的原签名、哈希和 nonce 在广播前以 AtomicFile 保存。重启后重新恢复签名地址与交易内容，核对钱包目录和来源；超时保留相同 raw，重发前先查收据。已有待确认记录可直接显示，不依赖最新仓位读取。成功或回滚均需至少 3 个区块确认，并核对收据所在区块的规范哈希后才清除待确认记录。3 个区块不等于 Ethereum 最终确定性。网络未知状态阻止继续签署另一笔 Ethereum 交易。

没有后台自动存款、赎回或自动签名。USDC 的余额和利息来自链上持仓，即使应用关闭仍按协议记账，但离线无法获取最新仓位、模拟或广播。此版本的确认查询需要用户打开 DeFi。卸载应用会丢失本地待确认 raw 和公开地址缓存；助记词可重新派生相同 Ethereum 地址，已上链仓位仍归该地址，可在兼容 BIP44 钱包恢复后管理。

当前功能未将 PRL / WPRL 声称为 Aave 已支持资产；Aave USDC 和 PearlBridge 是独立入口。跨链、WPRL 赎回、Uniswap 交易与 Lighter 下单仍使用原有外部签名流程。

## 验证与范围

测试包含 12 / 15 / 18 / 21 / 24 词地址与 ethers 6.17.0 的交叉核对、原签名在本地 EVM 的授权→存款→赎回和回滚测试、只读主网合约核验、Android 设备/密码验证、钱包隔离、原交易重试和旧版钱包升级。模拟链使用明确标记的模拟 USDC、aToken 和 Pool 合约，**不是生产 Aave 合约测试，也不是主网 fork**。未执行主网实币交易或独立安全审计，发布为开发测试 prerelease。

协议依据：[Aave V3 Pool 接口](https://www.aave.com/docs/aave-v3/smart-contracts/pool)、[Aave 提款说明](https://aave.com/help/supplying/withdraw-tokens)、[官方地址簿固定版本](https://github.com/aave-dao/aave-address-book/blob/e5bed1f0b32279b3f3259708b0a3b0fa29c058fc/src/AaveV3Ethereum.sol)、[储备 ABI 类型](https://github.com/aave-dao/aave-v3-origin/blob/8305565ae342f1773c42cd2e4593f175fe5968a0/src/contracts/protocol/libraries/types/DataTypes.sol)。
