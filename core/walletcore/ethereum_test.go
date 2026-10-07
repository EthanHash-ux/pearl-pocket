package walletcore

import (
	"bytes"
	"encoding/hex"
	"github.com/ethereum/go-ethereum/rlp"
	"math/big"
	"strings"
	"testing"
	"time"
)

func ethPlan(t *testing.T, op string) EthereumPlan {
	t.Helper()
	id, err := EthereumIdentity(make([]byte, 16))
	if err != nil {
		t.Fatal(err)
	}
	return EthereumPlan{Operation: op, From: id["address"], Amount: "1250000", ChainID: 1, Nonce: "0", Gas: "150000", MaxFee: "30000000000", Tip: "1000000000", Expires: time.Now().Unix() + 240}
}
func TestEthereumStandardDerivationAndPearlSeparation(t *testing.T) {
	id, err := EthereumIdentity(make([]byte, 16))
	if err != nil || id["address"] != "0x9858EfFD232B4033E47d90003D41EC34EcaEda94" || id["path"] != EthereumPath {
		t.Fatalf("BIP44 vector: %v %v", id, err)
	}
	ethKey, _, err := ethereumKey(make([]byte, 16))
	if err != nil {
		t.Fatal(err)
	}
	defer ethKey.Zero()
	pearlKey, _, err := keyFor(make([]byte, 16))
	if err != nil {
		t.Fatal(err)
	}
	defer pearlKey.Zero()
	if bytes.Equal(ethKey.Serialize(), pearlKey.Serialize()) {
		t.Fatal("Pearl and Ethereum keys reused")
	}
}
func TestEthereumCuratedSignaturesRoundtrip(t *testing.T) {
	for _, op := range []string{"approve", "supply", "withdraw"} {
		t.Run(op, func(t *testing.T) {
			p := ethPlan(t, op)
			signed, err := EthereumSign(make([]byte, 16), p)
			if err != nil {
				t.Fatal(err)
			}
			decoded, err := EthereumTransaction(signed["raw"])
			if err != nil {
				t.Fatal(err)
			}
			if decoded["from"] != p.From || decoded["operation"] != op || decoded["amount"] != p.Amount || signed["hash"] != decoded["hash"] || decoded["maxFeeWei"] != "4500000000000000" {
				t.Fatal(decoded)
			}
			again, err := EthereumSign(make([]byte, 16), p)
			if err != nil || signed["raw"] != again["raw"] {
				t.Fatal("retry signing is unstable", err)
			}
		})
	}
}
func TestEthereumRejectsWrongWalletAndUnsafePlan(t *testing.T) {
	p := ethPlan(t, "supply")
	cases := map[string]func(*EthereumPlan){
		"chain": func(p *EthereumPlan) { p.ChainID = 137 }, "operation": func(p *EthereumPlan) { p.Operation = "borrow" },
		"source": func(p *EthereumPlan) { p.From = EthereumUSDC }, "zero": func(p *EthereumPlan) { p.Amount = "0" },
		"unlimited": func(p *EthereumPlan) {
			p.Amount = new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 256), big.NewInt(1)).String()
		},
		"negative": func(p *EthereumPlan) { p.Amount = "-1" }, "leadingzero": func(p *EthereumPlan) { p.Nonce = "01" },
		"overflow": func(p *EthereumPlan) { p.Nonce = "18446744073709551616" },
		"gas":      func(p *EthereumPlan) { p.Gas = "1000001" }, "gaszero": func(p *EthereumPlan) { p.Gas = "0" },
		"fee": func(p *EthereumPlan) { p.MaxFee = "500000000001" }, "tip": func(p *EthereumPlan) { p.Tip = "30000000001" },
		"expired": func(p *EthereumPlan) { p.Expires = time.Now().Unix() }, "future": func(p *EthereumPlan) { p.Expires = time.Now().Unix() + 600 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			v := p
			change(&v)
			if _, err := EthereumSign(make([]byte, 16), v); err == nil {
				t.Fatal("unsafe plan accepted")
			}
		})
	}
	wrong := make([]byte, 16)
	wrong[0] = 1
	if _, err := EthereumSign(wrong, p); err == nil {
		t.Fatal("wrong entropy signed")
	}
}
func TestEthereumRejectsModifiedRecipientAndNoncanonicalRLP(t *testing.T) {
	signed, err := EthereumSign(make([]byte, 16), ethPlan(t, "supply"))
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := hex.DecodeString(signed["raw"][4:])
	var tx ethereumTx
	if err = rlp.DecodeBytes(raw, &tx); err != nil {
		t.Fatal(err)
	}
	mutation := func(name string, change func(*ethereumTx)) {
		t.Run(name, func(t *testing.T) {
			copy := tx
			copy.Data = bytes.Clone(tx.Data)
			change(&copy)
			encoded, e := rlp.EncodeToBytes(copy)
			if e != nil {
				t.Fatal(e)
			}
			if _, e = EthereumTransaction("0x02" + hex.EncodeToString(encoded)); e == nil {
				t.Fatal("modified transaction accepted")
			}
		})
	}
	mutation("recipient", func(v *ethereumTx) { v.Data[99] ^= 1 })
	mutation("referral", func(v *ethereumTx) { v.Data[131] = 1 })
	mutation("chain", func(v *ethereumTx) { v.ChainID = big.NewInt(10) })
	mutation("value", func(v *ethereumTx) { v.Value = big.NewInt(1) })
	mutation("parity", func(v *ethereumTx) { v.Parity = 2 })
	mutation("highs", func(v *ethereumTx) { v.S = new(big.Int).Lsh(big.NewInt(1), 255) })
	for _, s := range []string{"", "0x", "0x02", "0x02c0", "0x02ff", "0x02" + strings.Repeat("00", 1100), signed["raw"] + "00"} {
		if _, err := EthereumTransaction(s); err == nil {
			t.Fatal("malformed raw accepted", s[:min(len(s), 20)])
		}
	}
}
func FuzzEthereumTransaction(f *testing.F) {
	for _, s := range []string{"0x02", "0x02c0", "0x02f80180"} {
		f.Add(s)
	}
	p := EthereumPlan{Operation: "supply", From: "0x9858EfFD232B4033E47d90003D41EC34EcaEda94", Amount: "1250000", ChainID: 1, Nonce: "0", Gas: "150000", MaxFee: "30000000000", Tip: "1000000000", Expires: time.Now().Unix() + 240}
	signed, err := EthereumSign(make([]byte, 16), p)
	if err != nil {
		f.Fatal(err)
	}
	f.Add(signed["raw"])
	f.Fuzz(func(t *testing.T, s string) { EthereumTransaction(s) })
}
