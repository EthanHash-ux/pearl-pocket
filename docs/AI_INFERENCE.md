# PRL 支付 AI 推理 API · 首版

核对日期：2026-10-08。此模块是独立开发的商家支付协议，尚未部署公共商家、尚未使用主网实币或收费模型测试。代码包含 Android 客户端和可运行的 Linux 商家服务，不能把安装 APK 等同于商家已上线。

## 钱包中的流程

1. 首页「AI 推理服务」，输入受信任商家的 HTTPS 域名。绑定时核对商家名称、完整收款扩展公钥和域名；可使用商家邀请码创建账户，或导入已有 `prlai_` API Key。
2. 查看指定模型的 PRL 单次价格、输入字节上限、输出 token 上限，购买 1–1000 次调用。按调用次数预付，不按实际 token 动态扣 PRL；价格和次数在账单中固定，网络费另外展示。
3. 账单有效 15 分钟。核对完整地址、总金额、钱包和手续费，再验证系统 PIN / 密码与钱包密码。本机签署普通 Pearl 主网交易，收款人为商家。
4. 等待 6 个确认，点击「查询付款并开通额度」。付款交易必须在账单创建后的区块、账单有效时间内，收款地址及金额必须精确匹配。过期、付错金额或服务停服需联系商家处理；本协议没有自动退还 PRL。
5. 使用「试调用 AI API」提交文本，消耗 1 次额度；也可复制 API Key，在自己的程序中调用。提示词发送给商家和上游推理服务。API Key 不具有钱包转账权限。
6. 结果不明时查询原调用编号。同一账单存在未完成请求时，客户端与服务端都拒绝创建新的调用；商家核实上游结果后可补记回复或恢复 1 次额度。

API Key 和账单 / 调用编号由独立 Android Keystore AES-GCM 密钥加密，按手机钱包分别存储在禁止云备份的目录。解密 API 凭据不使用钱包助记词，也不要求每次系统认证；每次 PRL 付款仍须双重验证。设备密钥或应用数据丢失后，助记词只能恢复链上钱包，不能恢复商家 API Key。请自行保留 API Key 和账单编号。当前每钱包仅绑定一个商家，最多保存 100 张账单 / 100 次钱包试调用记录；尚无自助商家切换、API Key 撤销或账户迁移。

## 与 Pearl 官方推理平台的关系

官方 [计费文档](https://platform.pearlresearch.ai/docs/credits) 使用美元预付余额与 Stripe；目前未公布原生 PRL 付款接口。官方 [推理 API](https://platform.pearlresearch.ai/docs/concepts) 的 Base URL 是 `https://inference.pearlresearch.ai/v1`，使用 `sk-prl-infapi-` 组织 Key。这些 Key 与本协议的商家客户 Key 不相同。

商家可以采购官方 Pearl 推理服务、使用自己的模型，或使用其它兼容的文本 Chat Completions 上游。商家在自己的上游账户承担费用，客户以 PRL 向商家购买服务额度。本代码没有自动把 PRL 换成美元，也没有把客户 PRL 充值到官方平台。商家需确认上游服务使用及转售条件、配置有效凭据、可用模型和适合成本的 PRL 价格。

## 商家部署

需要一个独立的离线收款钱包、HTTPS 域名 / 服务器和兼容模型的有效 API Key。**不得把钱包助记词、xprv 或私钥配置在商家服务器。** 本应用客户钱包只使用 index 0，不能把商家收到的 index 1 及以上资金当作客户钱包余额；下面的离线工具支持这些收款地址的恢复和签名。

从项目 `core` 目录构建，依赖与 Android 签名核心使用同一固定 Pearl 上游提交：

```sh
go build -o ../artifacts/inference-gateway ./cmd/inference-gateway
go build -o ../artifacts/merchant-offline ./cmd/merchant-offline
go build -o ../artifacts/pearl-core-cli ./cmd/cli
```

在离线 Linux 终端执行 `merchant-offline`，隐藏输入自己的英文 BIP39 助记词，导出 `m/86'/808276'/0'` account 0 的公开 xpub 与 index 1 地址。不支持 BIP39 附加口令。终端输出只有公钥和地址，工具拒绝通过命令参数、文件或非终端 stdin 读取助记词。请核对地址并离线保管备份；不要使用测试向量或 Android 测试夹具的公钥收款。

复制 `services/inference-gateway.example.json` 到服务器私有配置目录，替换商家名、自己的 xpub、实际上游模型 ID、每次 PRL grain 价格和输入 / 输出上限。示例价格只用于说明格式，不是报价或收益保证。每个 grain 是 `0.00000001 PRL`，单次价允许 333–100000000 grain；当前输出上限 4096 token，输入上限 16384 字节。

通过受限环境文件或服务管理器提供以下变量，勿在源码或公开日志保存 Key：

| 变量 | 内容 |
| --- | --- |
| `PRL_GATEWAY_CONFIG` | 商家 JSON 配置文件路径 |
| `PRL_GATEWAY_LEDGER` | 账本文件路径，默认 `merchant-data/ledger.json` |
| `PRL_GATEWAY_UPSTREAM` | 上游 HTTPS API Base URL，例如官方 `https://inference.pearlresearch.ai/v1` |
| `PRL_GATEWAY_API_KEY` | 商家自己的上游 Key，仅存服务端 |
| `PRL_GATEWAY_SIGNUP_CODE` | 至少 16 字符的客户注册邀请码，私下发给客户 |
| `PRL_GATEWAY_BLOCKBOOK` | 可选的受信任 Pearl 主网 HTTPS Blockbook，默认官方索引 |

运行 `inference-gateway`，仅监听 `127.0.0.1:8787`。由 HTTPS 反向代理将自己的域名映射到该服务，配合连接数、请求大小和速率限制；客户端只允许标准 443 端口的 HTTPS 域名，不接受带凭据、路径、跳转或查询参数的服务地址。健康检查可读取 `GET /v1/catalog`。本仓库不提供已部署域名或商家邀请码。

服务为小规模试用的单进程账本：最多 1000 客户、5000 账单、1000 调用记录，超过限制拒绝新建。文件权限 0600、目录 0700，原子替换和 fsync 先于外部请求，进程通过文件锁避免多实例同时使用账本。保留账本和其备份，**不能删除账本后用相同 xpub 重新开业**，否则会重用收款地址。xpub 变更会阻止启动。客户 Key 只保存 SHA-256 摘要；账本保存额度和完成回复，不保存提示词。回复内容仍可能敏感，应保护服务器与备份并设置数据保留政策。

此首版未包含公开商业运营的水平扩容、自动链重组补偿、自助退款、商家目录、用户管理、Key 撤销与审计后台。商家收款账户的所有派生地址对掌握 xpub 的人可见。支付验证信任所配置 Blockbook，核对原始交易哈希、精确脚本金额、确认数、时间和规范区块，并在新调用前重查付款；这不是 SPV 或链上托管合约。

## API 协议

| 请求 | 用途 |
| --- | --- |
| `GET /v1/catalog` | 无认证商家信息、收款 xpub、模型价格与限制 |
| `POST /v1/accounts` | `{"signup_code":"..."}`；一次性返回客户 API Key |
| `POST /v1/invoices` | Bearer Key；`{"model":"...","request_count":10}` |
| `GET /v1/invoices/{id}` | 查询仅属于该客户的账单 |
| `POST /v1/invoices/{id}/claim` | `{"txid":"..."}`；确认支付并幂等开通额度 |
| `GET /v1/models` | Bearer Key；兼容的模型 ID 列表 |
| `POST /v1/chat/completions` | 文本、非流式，支持 model / messages / max_tokens / stream=false |
| `GET /v1/requests/{request-id}` | 原请求 pending / unknown / complete / refunded 及已保存回复 |

推理请求必须包含 `X-Pearl-Invoice` 和 `Idempotency-Key`。客户端超时后保持原编号并查询状态，不应换编号盲目重试；相同编号不同请求体返回 409，相同已完成请求返回缓存结果而不再执行。额度不足为 402。非文本内容、工具、额外参数、流式请求、超过购入上限的调用都会拒绝。

例如使用兼容 SDK（商家配置完成后）：

```python
import os, uuid
from openai import OpenAI

client = OpenAI(base_url=os.environ["MERCHANT_BASE_URL"] + "/v1",
                api_key=os.environ["MERCHANT_CUSTOMER_API_KEY"], max_retries=0)
request_id = str(uuid.uuid4())  # Save this before sending; retain it on timeout.
response = client.chat.completions.create(
    model="YOUR_PURCHASED_MODEL_ID",
    messages=[{"role": "user", "content": "Hello"}],
    max_tokens=512, stream=False,
    extra_headers={"X-Pearl-Invoice": os.environ["PAID_INVOICE_ID"],
                   "Idempotency-Key": request_id},
)
print(response.choices[0].message.content)
```

## 不明调用核对

服务端在请求上游前持久预留 1 次额度。任何请求 / 响应异常或进程崩溃都会保留预留，防止同一次推理重复收费或执行。核实上游使用记录后，停止服务，再使用同一配置和账本执行以下之一；文件锁确保不会与运行中的服务同时写账本：

```sh
inference-gateway --reconcile-account ACCOUNT_HASH --reconcile-request REQUEST_ID \
  --evidence 'provider usage record reference' --refund-known-failure

inference-gateway --reconcile-account ACCOUNT_HASH --reconcile-request REQUEST_ID \
  --evidence 'provider completion record reference' --confirmed-response response.json
```

`ACCOUNT_HASH` 来自账本客户摘要，`REQUEST_ID` 来自客户调用记录；不是原始 Key。恢复额度必须基于上游确认未履行该请求的证据；如果上游已处理，提供确认的标准回复 JSON。核对只能进行一次，保留证据引用；记录处理后再启动服务。未知结果不自动退额度，也不自动重放。

## 离线收款恢复

从账本查看收款账单的 `index`，使用只读工具从官方索引准备该地址的全部余额转账：

```sh
python3 scripts/merchant_prepare.py --core-cli artifacts/pearl-core-cli \
  --xpub YOUR_PUBLIC_ACCOUNT_XPUB --index INVOICE_INDEX \
  --to YOUR_PEARL_SETTLEMENT_ADDRESS --output transfer-quote.json
```

该工具只读取公开数据并准备带 5 分钟期限的报价，不读取种子、不签名、不广播。转账审核和官方 UTXO 校验由签名核心执行。将报价安全传到离线 Linux 终端，核对输入来源、完整收款地址、金额和费用后运行：

```sh
merchant-offline --index INVOICE_INDEX --sign-quote transfer-quote.json > signed.json
```

手动输入 `SIGN` 并在隐藏输入中填写商家助记词。工具验证所选 index 归属、报价期限、原始输入和官方脚本，输出原签名交易；仍不广播。再将公开签名交易安全传到联网环境，使用自己验证的 Pearl 节点 / Blockbook 提交原始 `raw`，核对返回 `txid`，失败时只查询或重发同一原交易。不要使用客户 Android 固定 index 0 的恢复流程来判断这些商家收款地址的余额。
