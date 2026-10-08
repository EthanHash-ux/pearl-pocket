package walletcore

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
	"time"

	"github.com/pearl-research-labs/pearl/node/btcec"
	"github.com/pearl-research-labs/pearl/node/btcec/ecdsa"
)

// Pearl Trade's public web3.js uses this fixed EIP-712 schema. This is an
// off-chain custodial ledger request, NOT an Ethereum transaction or a swap.
// Never accept arbitrary payloads, domains, hashes, contracts or recipients.
type TradePlan struct {
	From     string `json:"from"`
	Action   string `json:"action"`
	Side     string `json:"side,omitempty"`
	Quantity string `json:"quantity,omitempty"`
	Price    string `json:"price,omitempty"`
	Asset    string `json:"asset,omitempty"`
	Dest     string `json:"destination,omitempty"`
	OrderID  string `json:"orderId,omitempty"`
	Nonce    uint64 `json:"nonce"`
	IssuedAt int64  `json:"issuedAt"`
	Expires  int64  `json:"expires"`
}

type TradeAuth struct {
	Payload   string `json:"payload"`
	Nonce     uint64 `json:"nonce"`
	IssuedAt  int64  `json:"issued_at"`
	Signature string `json:"signature"`
	Address   string `json:"eth_address"`
}

func tradePayload(p TradePlan) (string, error) {
	if _, err := EthereumAddress(p.From); err != nil {
		return "", err
	}
	// JS sends numbers for nonce/time; restrict them to exact IEEE-754 integers.
	if p.Nonce == 0 || p.Nonce > 9007199254740991 || p.IssuedAt <= 0 || p.Expires < p.IssuedAt || p.Expires-p.IssuedAt > 300 {
		return "", errors.New("交易请求时间或 nonce 无效")
	}
	var body any
	switch p.Action {
	case "place_order":
		q, e := decimalUint(p.Quantity, 64)
		price, f := decimalUint(p.Price, 64)
		if e != nil || f != nil || q.Cmp(big.NewInt(100000000)) < 0 || q.Cmp(big.NewInt(2100000000000000)) > 0 || new(big.Int).Mod(q, big.NewInt(1000000)).Sign() != 0 || price.Sign() <= 0 || price.Cmp(big.NewInt(1000000000000)) > 0 || new(big.Int).Mod(price, big.NewInt(100)).Sign() != 0 || (p.Side != "buy" && p.Side != "sell") || p.Asset != "" || p.Dest != "" || p.OrderID != "" {
			return "", errors.New("PRL 限价、数量或请求字段无效")
		}
		body = struct {
			Market   string `json:"market"`
			Side     string `json:"side"`
			Price    string `json:"price_micro_per_prl"`
			Quantity string `json:"qty_sats"`
			Type     string `json:"type"`
		}{"PRL", p.Side, p.Price, p.Quantity, "limit"}
	case "cancel_order":
		id, e := decimalUint(p.OrderID, 53)
		if e != nil || id.Sign() <= 0 || p.Quantity != "" || p.Price != "" || p.Side != "" || p.Asset != "" || p.Dest != "" {
			return "", errors.New("撤单请求无效")
		}
		body = struct {
			ID uint64 `json:"order_id"`
		}{id.Uint64()}
	case "withdraw":
		q, e := decimalUint(p.Quantity, 64)
		if e != nil || q.Sign() <= 0 || q.Cmp(big.NewInt(2100000000000000)) > 0 || p.Price != "" || p.Side != "" || p.OrderID != "" {
			return "", errors.New("提现数量或请求字段无效")
		}
		if p.Asset == "USDC-ARB" {
			d, e := EthereumAddress(p.Dest)
			if e != nil || !strings.EqualFold(d, p.From) {
				return "", errors.New("USDC 只能提现到当前钱包的 Arbitrum 地址")
			}
		} else if p.Asset == "PRL" {
			if _, e := addressScript(p.Dest); e != nil {
				return "", errors.New("Pearl 提现地址无效")
			}
		} else {
			return "", errors.New("不支持此提现资产")
		}
		body = struct {
			Asset       string `json:"asset"`
			Destination string `json:"dest_address"`
			Amount      string `json:"amount_units"`
		}{p.Asset, p.Dest, p.Quantity}
	default:
		return "", errors.New("不支持此现货操作")
	}
	b, err := json.Marshal(body)
	return string(b), err
}

func tradeDigest(p TradePlan, payload string) []byte {
	domain := append(keccak([]byte("EIP712Domain(string name,string version)")), keccak([]byte("Pearl OTC"))...)
	domain = append(domain, keccak([]byte("1"))...)
	body := append(keccak([]byte("Request(string action,bytes32 payloadHash,uint256 nonce,uint256 issuedAt)")), keccak([]byte(p.Action))...)
	body = append(body, keccak([]byte(payload))...)
	body = append(body, abiWord(new(big.Int).SetUint64(p.Nonce).Bytes())...)
	body = append(body, abiWord(big.NewInt(p.IssuedAt).Bytes())...)
	return keccak(append(append([]byte{0x19, 0x01}, keccak(domain)...), keccak(body)...))
}

func TradeIntent(p TradePlan) (map[string]string, error) {
	payload, e := tradePayload(p)
	if e != nil {
		return nil, e
	}
	return map[string]string{"payload": payload, "digest": "0x" + hex.EncodeToString(tradeDigest(p, payload)), "domain": "Pearl OTC", "version": "1"}, nil
}

func TradeSign(b []byte, p TradePlan) (TradeAuth, error) {
	var empty TradeAuth
	now := time.Now().Unix()
	if p.IssuedAt > now+30 || now-p.IssuedAt > 300 || p.Expires <= now || p.Expires > now+300 {
		return empty, errors.New("现货操作预览已过期，请重新核对")
	}
	payload, e := tradePayload(p)
	if e != nil {
		return empty, e
	}
	key, from, e := ethereumKey(b)
	if e != nil {
		return empty, e
	}
	defer key.Zero()
	if !strings.EqualFold(p.From, from) {
		return empty, errors.New("现货账户不属于当前钱包")
	}
	if p.Action == "withdraw" && p.Asset == "PRL" {
		k, a, e := keyFor(b)
		if e != nil {
			return empty, e
		}
		k.Zero()
		if a != p.Dest {
			return empty, errors.New("PRL 只能提现到当前钱包")
		}
	}
	s := ecdsa.SignCompact(key, tradeDigest(p, payload), false)
	defer wipe(s)
	if s[0] < 27 || s[0] > 28 {
		return empty, errors.New("现货签名恢复标记无效")
	}
	signature := append(append([]byte{}, s[1:]...), s[0])
	a := TradeAuth{payload, p.Nonce, p.IssuedAt, "0x" + hex.EncodeToString(signature), from}
	if _, e = TradeVerify(p, a); e != nil {
		return empty, e
	}
	return a, nil
}

// Validation remains usable after expiry to recover saved requests. It cannot
// create signatures. Submission separately checks the short local lifetime.
func TradeVerify(p TradePlan, a TradeAuth) (map[string]string, error) {
	payload, e := tradePayload(p)
	if e != nil {
		return nil, e
	}
	if payload != a.Payload || a.Nonce != p.Nonce || a.IssuedAt != p.IssuedAt || !strings.EqualFold(a.Address, p.From) || !strings.HasPrefix(a.Signature, "0x") || len(a.Signature) != 132 {
		return nil, errors.New("现货签名与已审核请求不一致")
	}
	s, e := hex.DecodeString(a.Signature[2:])
	if e != nil || (s[64] != 27 && s[64] != 28) {
		return nil, errors.New("现货签名格式无效")
	}
	compact := append([]byte{s[64]}, s[:64]...)
	key, _, e := ecdsa.RecoverCompact(compact, tradeDigest(p, payload))
	if e != nil || !strings.EqualFold(ethereumPublicAddress(key), p.From) {
		return nil, errors.New("现货签名账户校验失败")
	}
	// Signatures returned by this wallet are low-S and canonical.
	order := btcec.S256().Params().N
	if new(big.Int).SetBytes(s[32:64]).Cmp(new(big.Int).Rsh(new(big.Int).Set(order), 1)) > 0 {
		return nil, errors.New("现货签名数值无效")
	}
	return map[string]string{"from": ethereumPublicAddress(key), "action": p.Action, "payload": payload}, nil
}
