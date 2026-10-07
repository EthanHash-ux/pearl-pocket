package walletcore

import (
	"bytes"
	"encoding/hex"
	"errors"
	"math/big"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/rlp"
	"github.com/pearl-research-labs/pearl/node/btcec"
	"github.com/pearl-research-labs/pearl/node/btcec/ecdsa"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	bip39 "github.com/tyler-smith/go-bip39"
	"golang.org/x/crypto/sha3"
)

const EthereumPath = "m/44'/60'/0'/0/0"
const AavePool = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
const EthereumUSDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

// Only these three USDC liquidity operations can be signed. No arbitrary dApp calls.
type EthereumPlan struct {
	Operation string `json:"operation"`
	From      string `json:"from"`
	Amount    string `json:"amount"`
	ChainID   uint64 `json:"chainId"`
	Nonce     string `json:"nonce"`
	Gas       string `json:"gas"`
	MaxFee    string `json:"maxFeePerGas"`
	Tip       string `json:"maxPriorityFeePerGas"`
	Expires   int64  `json:"expires"`
}

type ethereumTx struct {
	ChainID    *big.Int
	Nonce      uint64
	Tip        *big.Int
	Fee        *big.Int
	Gas        uint64
	To         []byte
	Value      *big.Int
	Data       []byte
	AccessList []rlp.RawValue
	Parity     uint8
	R          *big.Int
	S          *big.Int
}

func keccak(data []byte) []byte { h := sha3.NewLegacyKeccak256(); h.Write(data); return h.Sum(nil) }

func ethereumKey(b []byte) (*btcec.PrivateKey, string, error) {
	words, err := bip39.NewMnemonic(b)
	if err != nil {
		return nil, "", err
	}
	seed := bip39.NewSeed(words, "")
	defer wipe(seed)
	k, err := hdkeychain.NewMaster(seed, &chaincfg.MainNetParams)
	if err != nil {
		return nil, "", err
	}
	for _, index := range []uint32{44 + hdkeychain.HardenedKeyStart, 60 + hdkeychain.HardenedKeyStart, hdkeychain.HardenedKeyStart, 0, 0} {
		child, e := k.Derive(index)
		k.Zero()
		if e != nil {
			return nil, "", e
		}
		k = child
	}
	defer k.Zero()
	key, err := k.ECPrivKey()
	if err != nil {
		return nil, "", err
	}
	return key, ethereumPublicAddress(key.PubKey()), nil
}

func ethereumPublicAddress(key *btcec.PublicKey) string {
	a, _ := EthereumAddress("0x" + hex.EncodeToString(keccak(key.SerializeUncompressed()[1:])[12:]))
	return a
}

func EthereumIdentity(b []byte) (map[string]string, error) {
	key, address, err := ethereumKey(b)
	if err != nil {
		return nil, err
	}
	key.Zero()
	return map[string]string{"address": address, "path": EthereumPath}, nil
}

func decimalUint(s string, bits int) (*big.Int, error) {
	if s == "" || len(s) > 78 || len(s) > 1 && s[0] == '0' {
		return nil, errors.New("Ethereum 整数格式无效")
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return nil, errors.New("Ethereum 整数格式无效")
		}
	}
	v, ok := new(big.Int).SetString(s, 10)
	if !ok || v.BitLen() > bits {
		return nil, errors.New("Ethereum 数值超出范围")
	}
	return v, nil
}

func abiWord(b []byte) []byte        { return append(make([]byte, 32-len(b)), b...) }
func ethBytes(address string) []byte { b, _ := hex.DecodeString(address[2:]); return b }

func ethereumIntent(p EthereumPlan) (string, []byte, error) {
	if p.ChainID != 1 {
		return "", nil, errors.New("仅支持 Ethereum 主网")
	}
	from, err := EthereumAddress(p.From)
	if err != nil {
		return "", nil, err
	}
	amount, err := decimalUint(p.Amount, 256)
	if err != nil || amount.Sign() <= 0 || amount.Cmp(new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 256), big.NewInt(1))) == 0 {
		return "", nil, errors.New("请输入明确的正数 USDC 金额")
	}
	var selector string
	to := AavePool
	var args []byte
	switch p.Operation {
	case "approve":
		selector = "095ea7b3"
		to = EthereumUSDC
		args = append(abiWord(ethBytes(AavePool)), abiWord(amount.Bytes())...)
	case "supply":
		selector = "617ba037"
		args = append(abiWord(ethBytes(EthereumUSDC)), abiWord(amount.Bytes())...)
		args = append(args, abiWord(ethBytes(from))...)
		args = append(args, make([]byte, 32)...)
	case "withdraw":
		selector = "69328dec"
		args = append(abiWord(ethBytes(EthereumUSDC)), abiWord(amount.Bytes())...)
		args = append(args, abiWord(ethBytes(from))...)
	default:
		return "", nil, errors.New("不支持此 DeFi 操作")
	}
	method, _ := hex.DecodeString(selector)
	return to, append(method, args...), nil
}

func EthereumIntent(p EthereumPlan) (map[string]any, error) {
	to, data, err := ethereumIntent(p)
	if err != nil {
		return nil, err
	}
	return map[string]any{"chainId": 1, "from": p.From, "to": to, "value": "0x0", "data": "0x" + hex.EncodeToString(data), "operation": p.Operation, "amount": p.Amount}, nil
}

func ethereumUnsigned(tx ethereumTx) ([]byte, error) {
	return rlp.EncodeToBytes([]any{tx.ChainID, tx.Nonce, tx.Tip, tx.Fee, tx.Gas, tx.To, tx.Value, tx.Data, tx.AccessList})
}

func EthereumSign(b []byte, p EthereumPlan) (map[string]string, error) {
	if p.Expires <= time.Now().Unix() || p.Expires > time.Now().Unix()+300 {
		return nil, errors.New("DeFi 转账预览已过期，请重新核对")
	}
	to, data, err := ethereumIntent(p)
	if err != nil {
		return nil, err
	}
	nonce, err := decimalUint(p.Nonce, 64)
	if err != nil {
		return nil, err
	}
	gas, err := decimalUint(p.Gas, 64)
	if err != nil || gas.Cmp(big.NewInt(21000)) < 0 || gas.Cmp(big.NewInt(1000000)) > 0 {
		return nil, errors.New("Ethereum gas 限额无效")
	}
	fee, err := decimalUint(p.MaxFee, 64)
	if err != nil || fee.Sign() <= 0 || fee.Cmp(big.NewInt(500000000000)) > 0 {
		return nil, errors.New("Ethereum 最高 gas 单价超出允许范围")
	}
	tip, err := decimalUint(p.Tip, 64)
	if err != nil || tip.Cmp(fee) > 0 {
		return nil, errors.New("Ethereum 优先费无效")
	}
	key, from, err := ethereumKey(b)
	if err != nil {
		return nil, err
	}
	defer key.Zero()
	if !strings.EqualFold(from, p.From) {
		return nil, errors.New("Ethereum 付款地址不属于所选钱包")
	}
	tx := ethereumTx{ChainID: big.NewInt(1), Nonce: nonce.Uint64(), Tip: tip, Fee: fee, Gas: gas.Uint64(), To: ethBytes(to), Value: big.NewInt(0), Data: data, AccessList: []rlp.RawValue{}}
	unsigned, err := ethereumUnsigned(tx)
	if err != nil {
		return nil, err
	}
	sig := ecdsa.SignCompact(key, keccak(append([]byte{2}, unsigned...)), false)
	defer wipe(sig)
	if sig[0] < 27 || sig[0] > 28 {
		return nil, errors.New("Ethereum 签名恢复标记无效")
	}
	tx.Parity = sig[0] - 27
	tx.R = new(big.Int).SetBytes(sig[1:33])
	tx.S = new(big.Int).SetBytes(sig[33:65])
	encoded, err := rlp.EncodeToBytes(tx)
	if err != nil {
		return nil, err
	}
	raw := append([]byte{2}, encoded...)
	// Decode, recover and validate every signed transaction before returning it.
	result, err := EthereumTransaction("0x" + hex.EncodeToString(raw))
	if err != nil {
		return nil, err
	}
	if result["from"] != from {
		return nil, errors.New("Ethereum 签名地址校验失败")
	}
	return result, nil
}

func EthereumTransaction(input string) (map[string]string, error) {
	if !strings.HasPrefix(input, "0x02") || len(input) > 2048 {
		return nil, errors.New("Ethereum 签名交易格式无效")
	}
	raw, err := hex.DecodeString(input[2:])
	if err != nil {
		return nil, errors.New("Ethereum 签名交易编码无效")
	}
	var tx ethereumTx
	if err = rlp.DecodeBytes(raw[1:], &tx); err != nil {
		return nil, errors.New("Ethereum 签名交易解码失败")
	}
	if tx.ChainID.Cmp(big.NewInt(1)) != 0 || len(tx.To) != 20 || tx.Value.Sign() != 0 || len(tx.AccessList) != 0 || tx.Parity > 1 || tx.Gas < 21000 || tx.Gas > 1000000 || tx.Fee.Sign() <= 0 || tx.Fee.Cmp(big.NewInt(500000000000)) > 0 || tx.Tip.Sign() < 0 || tx.Tip.Cmp(tx.Fee) > 0 {
		return nil, errors.New("Ethereum 交易参数超出支持范围")
	}
	n := btcec.S256().Params().N
	if tx.R.Sign() <= 0 || tx.R.Cmp(n) >= 0 || tx.S.Sign() <= 0 || tx.S.Cmp(new(big.Int).Rsh(new(big.Int).Set(n), 1)) > 0 {
		return nil, errors.New("Ethereum 签名数值无效")
	}
	unsigned, err := ethereumUnsigned(tx)
	if err != nil {
		return nil, err
	}
	sig := append([]byte{27 + tx.Parity}, abiWord(tx.R.Bytes())...)
	sig = append(sig, abiWord(tx.S.Bytes())...)
	pub, _, err := ecdsa.RecoverCompact(sig, keccak(append([]byte{2}, unsigned...)))
	if err != nil {
		return nil, errors.New("Ethereum 签名校验失败")
	}
	from := ethereumPublicAddress(pub)
	to := "0x" + hex.EncodeToString(tx.To)
	var operation, amount string
	if to == EthereumUSDC && len(tx.Data) == 68 && hex.EncodeToString(tx.Data[:4]) == "095ea7b3" && bytes.Equal(tx.Data[4:36], abiWord(ethBytes(AavePool))) {
		operation = "approve"
		amount = new(big.Int).SetBytes(tx.Data[36:]).String()
	}
	if to == AavePool && (len(tx.Data) == 100 || len(tx.Data) == 132) && bytes.Equal(tx.Data[4:36], abiWord(ethBytes(EthereumUSDC))) && bytes.Equal(tx.Data[68:100], abiWord(ethBytes(from))) {
		selector := hex.EncodeToString(tx.Data[:4])
		if selector == "617ba037" && len(tx.Data) == 132 && bytes.Equal(tx.Data[100:], make([]byte, 32)) {
			operation = "supply"
		}
		if selector == "69328dec" && len(tx.Data) == 100 {
			operation = "withdraw"
		}
		amount = new(big.Int).SetBytes(tx.Data[36:68]).String()
	}
	p := EthereumPlan{Operation: operation, Amount: amount, From: from, ChainID: 1}
	expectedTo, expectedData, err := ethereumIntent(p)
	if err != nil || to != expectedTo || !bytes.Equal(tx.Data, expectedData) {
		return nil, errors.New("Ethereum 交易不是受支持的借贷流动性操作")
	}
	return map[string]string{"raw": "0x" + hex.EncodeToString(raw), "hash": "0x" + hex.EncodeToString(keccak(raw)), "from": from, "to": to, "operation": operation, "amount": amount, "nonce": new(big.Int).SetUint64(tx.Nonce).String(), "maxFeeWei": new(big.Int).Mul(new(big.Int).SetUint64(tx.Gas), tx.Fee).String()}, nil
}
