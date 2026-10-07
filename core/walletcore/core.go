// Package walletcore uses the pinned Pearl implementation for key derivation,
// transaction serialization, Schnorr signing and script verification.
package walletcore

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/pearl-research-labs/pearl/node/btcec"
	"github.com/pearl-research-labs/pearl/node/btcec/schnorr"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/chaincfg/chainhash"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	bip39 "github.com/tyler-smith/go-bip39"
	"golang.org/x/crypto/sha3"
)

const Path = "m/86'/808276'/0'/0/0"
const MaxMoney int64 = 210000000000000000
const Dust int64 = 333

// EthereumAddress validates an external public address, including EIP-55 when
// mixed case is supplied. It does not derive or use an Ethereum private key.
func EthereumAddress(input string) (string, error) {
	input = strings.TrimSpace(input)
	if len(input) != 42 || !strings.HasPrefix(input, "0x") {
		return "", errors.New("请输入完整的 Ethereum 主网地址")
	}
	raw, err := hex.DecodeString(input[2:])
	if err != nil || bytes.Equal(raw, make([]byte, 20)) {
		return "", errors.New("Ethereum 地址无效")
	}
	lower := strings.ToLower(input[2:])
	h := sha3.NewLegacyKeccak256()
	h.Write([]byte(lower))
	digest := hex.EncodeToString(h.Sum(nil))
	checksummed := []byte(lower)
	for i, c := range checksummed {
		if c >= 'a' && c <= 'f' && digest[i] >= '8' {
			checksummed[i] = c - 32
		}
	}
	result := "0x" + string(checksummed)
	if input[2:] != lower && input[2:] != strings.ToUpper(lower) && input != result {
		return "", errors.New("Ethereum 地址校验和不匹配，请重新复制")
	}
	return result, nil
}

type UTXO struct {
	TxID          string `json:"txid"`
	Vout          uint32 `json:"vout"`
	Raw           string `json:"raw"`
	Confirmations int64  `json:"confirmations"`
}
type Payment struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Amount int64  `json:"amount,string"`
	Rate   int64  `json:"rate"`
	UTXOs  []UTXO `json:"utxos"`
	Sweep  bool   `json:"sweep,omitempty"`
}
type Quote struct {
	Payment Payment `json:"payment"`
	Fee     int64   `json:"fee,string"`
	Change  int64   `json:"change,string"`
	VSize   int     `json:"vsize"`
	Digest  string  `json:"digest"`
	Expires int64   `json:"expires"`
}
type Request struct {
	Action   string       `json:"action"`
	Entropy  string       `json:"entropy"`
	Mnemonic string       `json:"mnemonic"`
	Raw      string       `json:"raw"`
	Payment  Payment      `json:"payment"`
	Quote    Quote        `json:"quote"`
	Ethereum EthereumPlan `json:"ethereum"`
}
type Identity struct {
	Address  string `json:"address"`
	Entropy  string `json:"entropy"`
	Mnemonic string `json:"mnemonic"`
	Path     string `json:"path"`
}
type Signed struct {
	Raw   string `json:"raw"`
	TxID  string `json:"txid"`
	Fee   int64  `json:"fee,string"`
	VSize int    `json:"vsize"`
}
type selected struct {
	Outpoint wire.OutPoint
	Out      *wire.TxOut
	Source   UTXO
}

func wipe(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
func entropy(text string) ([]byte, error) {
	b, e := base64.StdEncoding.DecodeString(text)
	if e != nil || len(b) < 16 || len(b) > 32 || len(b)%4 != 0 {
		wipe(b)
		return nil, errors.New("助记词数据无效")
	}
	return b, nil
}
func keyFor(b []byte) (*btcec.PrivateKey, string, error) {
	mnemonic, e := bip39.NewMnemonic(b)
	if e != nil {
		return nil, "", e
	}
	seed := bip39.NewSeed(mnemonic, "")
	defer wipe(seed)
	k, e := hdkeychain.NewMaster(seed, &chaincfg.MainNetParams)
	if e != nil {
		return nil, "", e
	}
	for _, i := range []uint32{86 + hdkeychain.HardenedKeyStart, 808276 + hdkeychain.HardenedKeyStart, hdkeychain.HardenedKeyStart, 0, 0} {
		child, err := k.DeriveNonStandard(i)
		k.Zero()
		if err != nil {
			return nil, "", err
		}
		k = child
	}
	defer k.Zero()
	priv, e := k.ECPrivKey()
	if e != nil {
		return nil, "", e
	}
	pub := txscript.ComputeTaprootKeyNoScript(priv.PubKey())
	addr, e := btcutil.NewAddressTaproot(schnorr.SerializePubKey(pub), &chaincfg.MainNetParams)
	if e != nil {
		priv.Zero()
		return nil, "", e
	}
	return priv, addr.EncodeAddress(), nil
}
func identify(b []byte) (Identity, error) {
	priv, addr, e := keyFor(b)
	if e != nil {
		return Identity{}, e
	}
	priv.Zero()
	m, e := bip39.NewMnemonic(b)
	return Identity{addr, base64.StdEncoding.EncodeToString(b), m, Path}, e
}
func addressScript(s string) ([]byte, error) {
	a, e := btcutil.DecodeAddress(s, &chaincfg.MainNetParams)
	if e != nil || !a.IsForNet(&chaincfg.MainNetParams) {
		return nil, errors.New("请输入有效的 Pearl 主网地址")
	}
	switch a.(type) {
	case *btcutil.AddressTaproot, *btcutil.AddressMerkleRoot:
	default:
		return nil, errors.New("请输入 Pearl Taproot 或 P2MR 主网地址")
	}
	return txscript.PayToAddrScript(a)
}
func deserialize(s string) (*wire.MsgTx, error) {
	if len(s) > 800000 {
		return nil, errors.New("交易数据过大")
	}
	b, e := hex.DecodeString(s)
	if e != nil {
		return nil, errors.New("交易编码无效")
	}
	r := bytes.NewReader(b)
	tx := wire.NewMsgTx(2)
	if e = tx.Deserialize(r); e != nil || r.Len() != 0 {
		return nil, errors.New("交易格式无效")
	}
	return tx, nil
}
func serialize(tx *wire.MsgTx) []byte { var b bytes.Buffer; _ = tx.Serialize(&b); return b.Bytes() }
func vsize(tx *wire.MsgTx) int        { return (tx.SerializeSizeStripped()*3 + tx.SerializeSize() + 3) / 4 }
func digest(tx *wire.MsgTx) string {
	h := sha256.Sum256(serialize(tx))
	return hex.EncodeToString(h[:])
}
func candidatesFor(p Payment) ([]byte, []byte, []selected, error) {
	if p.Amount < Dust || p.Amount > MaxMoney {
		return nil, nil, nil, errors.New("金额必须至少为 0.00000333 PRL，且不能超过总供应量")
	}
	if p.Rate < 1000 || p.Rate > 1000000 {
		return nil, nil, nil, errors.New("手续费率超出允许范围")
	}
	if len(p.UTXOs) == 0 || len(p.UTXOs) > 200 {
		return nil, nil, nil, errors.New("没有可用的已确认余额，或输入过多")
	}
	own, e := addressScript(p.From)
	if e != nil {
		return nil, nil, nil, e
	}
	to, e := addressScript(p.To)
	if e != nil {
		return nil, nil, nil, e
	}
	if bytes.Equal(own, to) {
		return nil, nil, nil, errors.New("收款地址与自己的地址相同")
	}
	candidates := make([]selected, 0, len(p.UTXOs))
	seen := make(map[wire.OutPoint]bool)
	for _, u := range p.UTXOs {
		hash, e := chainhash.NewHashFromStr(u.TxID)
		if e != nil || len(u.TxID) != 64 {
			return nil, nil, nil, errors.New("输入交易 ID 无效")
		}
		op := wire.OutPoint{Hash: *hash, Index: u.Vout}
		if seen[op] {
			return nil, nil, nil, errors.New("出现重复的交易输入")
		}
		seen[op] = true
		prev, e := deserialize(u.Raw)
		if e != nil {
			return nil, nil, nil, e
		}
		if prev.TxHash() != *hash || int(u.Vout) >= len(prev.TxOut) {
			return nil, nil, nil, errors.New("输入交易与原始数据不匹配")
		}
		out := prev.TxOut[u.Vout]
		if !bytes.Equal(out.PkScript, own) || out.Value <= 0 || out.Value > MaxMoney {
			return nil, nil, nil, errors.New("交易输入不属于此钱包或金额无效")
		}
		coinbase := len(prev.TxIn) == 1 && prev.TxIn[0].PreviousOutPoint.Hash == (chainhash.Hash{}) && prev.TxIn[0].PreviousOutPoint.Index == 0xffffffff
		if u.Confirmations < 1 || (coinbase && u.Confirmations < int64(chaincfg.MainNetParams.CoinbaseMaturity)) {
			continue
		}
		candidates = append(candidates, selected{op, out, u})
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].Out.Value != candidates[j].Out.Value {
			return candidates[i].Out.Value > candidates[j].Out.Value
		}
		return candidates[i].Outpoint.String() < candidates[j].Outpoint.String()
	})
	return own, to, candidates, nil
}
func build(p Payment) (*wire.MsgTx, []selected, int64, int64, error) {
	own, to, candidates, e := candidatesFor(p)
	if e != nil {
		return nil, nil, 0, 0, e
	}
	if p.Sweep {
		if len(candidates) == 0 || len(candidates) > 100 {
			return nil, nil, 0, 0, errors.New("发送全部需要 1–100 个可用输入，请先合并过多输入")
		}
		tx, total, e := sweepTemplate(candidates, to, p.Rate)
		if e != nil {
			return nil, nil, 0, 0, e
		}
		fee := (int64(vsize(tx))*p.Rate + 999) / 1000
		if total-p.Amount != fee {
			return nil, nil, 0, 0, errors.New("全部余额与手续费预览不一致")
		}
		tx.TxOut[0].Value = p.Amount
		return tx, candidates, fee, 0, nil
	}
	tx := wire.NewMsgTx(2)
	tx.AddTxOut(wire.NewTxOut(p.Amount, to))
	tx.AddTxOut(wire.NewTxOut(Dust, own))
	var total int64
	chosen := make([]selected, 0)
	for _, c := range candidates {
		if len(chosen) >= 100 {
			break
		}
		if total > MaxMoney-c.Out.Value {
			return nil, nil, 0, 0, errors.New("输入总金额无效")
		}
		total += c.Out.Value
		chosen = append(chosen, c)
		in := wire.NewTxIn(&c.Outpoint, nil, wire.TxWitness{make([]byte, 64)})
		tx.AddTxIn(in)
		fee := (int64(vsize(tx))*p.Rate + 999) / 1000
		if total >= p.Amount+fee+Dust {
			tx.TxOut[1].Value = total - p.Amount - fee
			return tx, chosen, fee, tx.TxOut[1].Value, nil
		}
		// Recalculate without change; any remainder is included in the displayed fee.
		changeOut := tx.TxOut[1]
		tx.TxOut = tx.TxOut[:1]
		minFee := (int64(vsize(tx))*p.Rate + 999) / 1000
		if total >= p.Amount+minFee {
			return tx, chosen, total - p.Amount, 0, nil
		}
		tx.AddTxOut(changeOut)
	}
	return nil, nil, 0, 0, errors.New("已确认余额不足以支付金额和手续费")
}
func sweepTemplate(candidates []selected, to []byte, rate int64) (*wire.MsgTx, int64, error) {
	tx := wire.NewMsgTx(2)
	tx.AddTxOut(wire.NewTxOut(Dust, to))
	var total int64
	for _, c := range candidates {
		if total > MaxMoney-c.Out.Value {
			return nil, 0, errors.New("输入总金额无效")
		}
		total += c.Out.Value
		tx.AddTxIn(wire.NewTxIn(&c.Outpoint, nil, wire.TxWitness{make([]byte, 64)}))
	}
	return tx, total, nil
}

// PlanMaximum spends exactly all eligible inputs with one output and no change.
// It rejects more than 100 inputs rather than silently leaving part of the balance.
func PlanMaximum(p Payment) (Quote, error) {
	p.Amount = Dust
	p.Sweep = true
	_, to, candidates, e := candidatesFor(p)
	if e != nil {
		return Quote{}, e
	}
	if len(candidates) == 0 || len(candidates) > 100 {
		return Quote{}, errors.New("发送全部需要 1–100 个可用输入，请先合并过多输入")
	}
	tx, total, e := sweepTemplate(candidates, to, p.Rate)
	if e != nil {
		return Quote{}, e
	}
	p.Amount = total - (int64(vsize(tx))*p.Rate+999)/1000
	if p.Amount < Dust {
		return Quote{}, errors.New("可用余额不足以支付手续费和最低收款金额")
	}
	return Plan(p)
}
func Plan(p Payment) (Quote, error) {
	tx, _, fee, change, e := build(p)
	if e != nil {
		return Quote{}, e
	}
	return Quote{p, fee, change, vsize(tx), digest(tx), time.Now().Unix() + 300}, nil
}
func Sign(b []byte, q Quote) (Signed, error) {
	if q.Expires < time.Now().Unix() || q.Expires > time.Now().Unix()+310 {
		return Signed{}, errors.New("转账预览已过期，请重新确认")
	}
	key, addr, e := keyFor(b)
	if e != nil {
		return Signed{}, e
	}
	defer key.Zero()
	if addr != q.Payment.From {
		return Signed{}, errors.New("密钥与钱包地址不匹配")
	}
	tx, chosen, fee, change, e := build(q.Payment)
	if e != nil {
		return Signed{}, e
	}
	if q.Fee != fee || q.Change != change || q.VSize != vsize(tx) || q.Digest != digest(tx) {
		return Signed{}, errors.New("交易预览被更改，请重新确认")
	}
	fetch := txscript.NewMultiPrevOutFetcher(nil)
	for _, c := range chosen {
		fetch.AddPrevOut(c.Outpoint, c.Out)
	}
	hashes := txscript.NewTxSigHashes(tx, fetch)
	for i, c := range chosen {
		tx.TxIn[i].Witness, e = txscript.TaprootWitnessSignature(tx, hashes, i, c.Out.Value, c.Out.PkScript, txscript.SigHashDefault, key)
		if e != nil {
			return Signed{}, errors.New("本地签名失败")
		}
	}
	for i, c := range chosen {
		engine, err := txscript.NewEngine(c.Out.PkScript, tx, i, txscript.StandardVerifyFlags, nil, hashes, c.Out.Value, fetch)
		if err != nil {
			return Signed{}, err
		}
		if err = engine.Execute(); err != nil {
			return Signed{}, fmt.Errorf("官方脚本校验失败: %w", err)
		}
	}
	return Signed{hex.EncodeToString(serialize(tx)), tx.TxHash().String(), fee, vsize(tx)}, nil
}
func Execute(input string) (output string) {
	defer func() {
		if recover() != nil {
			output = `{"error":"签名核心拒绝了无效请求"}`
		}
	}()
	if len(input) > 32000000 {
		return `{"error":"请求数据过大"}`
	}
	var r Request
	var result any
	var e error
	if e = json.Unmarshal([]byte(input), &r); e != nil {
		return `{"error":"请求格式无效"}`
	}
	switch r.Action {
	case "ethintent":
		result, e = EthereumIntent(r.Ethereum)
	case "ethtransaction":
		result, e = EthereumTransaction(r.Raw)
	case "ethaddress":
		var address string
		address, e = EthereumAddress(r.Raw)
		result = map[string]string{"address": address}
	case "generate":
		b := make([]byte, 32)
		_, e = rand.Read(b)
		if e == nil {
			result, e = identify(b)
		}
		wipe(b)
	case "import":
		m := strings.Join(strings.Fields(strings.ToLower(r.Mnemonic)), " ")
		n := len(strings.Fields(m))
		if n < 12 || n > 24 || n%3 != 0 || !bip39.IsMnemonicValid(m) {
			e = errors.New("请输入有效的 12、15、18、21 或 24 个 BIP39 英文助记词")
		} else {
			var b []byte
			b, e = bip39.EntropyFromMnemonic(m)
			if e == nil {
				result, e = identify(b)
			}
			wipe(b)
		}
	case "identity", "address", "sign", "ethidentity", "ethsign":
		var b []byte
		b, e = entropy(r.Entropy)
		if e == nil {
			if r.Action == "ethidentity" {
				result, e = EthereumIdentity(b)
			} else if r.Action == "ethsign" {
				result, e = EthereumSign(b, r.Ethereum)
			} else if r.Action == "address" {
				var key *btcec.PrivateKey
				var addr string
				key, addr, e = keyFor(b)
				if e == nil {
					key.Zero()
					result = map[string]string{"address": addr, "path": Path}
				}
			} else if r.Action == "identity" {
				result, e = identify(b)
			} else {
				result, e = Sign(b, r.Quote)
			}
		}
		wipe(b)
	case "plan":
		result, e = Plan(r.Payment)
	case "planmax":
		result, e = PlanMaximum(r.Payment)
	case "transaction":
		var tx *wire.MsgTx
		tx, e = deserialize(r.Raw)
		if e == nil {
			result = map[string]string{"txid": tx.TxHash().String()}
		}
	default:
		e = errors.New("不支持的签名操作")
	}
	if e != nil {
		raw, _ := json.Marshal(map[string]string{"error": e.Error()})
		return string(raw)
	}
	raw, e := json.Marshal(map[string]any{"result": result})
	if e != nil {
		return `{"error":"无法编码签名结果"}`
	}
	return string(raw)
}
