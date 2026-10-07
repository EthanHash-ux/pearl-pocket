// Test-only public fixture. These known keys must never hold real funds.
package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/chaincfg/chainhash"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	"pearlwallet/walletcore"
)

func id(entropy string) walletcore.Identity {
	var r struct {
		Result walletcore.Identity `json:"result"`
	}
	_ = json.Unmarshal([]byte(walletcore.Execute(`{"action":"identity","entropy":"`+entropy+`"}`)), &r)
	return r.Result
}
func main() {
	a := id("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
	b := id("AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
	addr, _ := btcutil.DecodeAddress(a.Address, &chaincfg.MainNetParams)
	script, _ := txscript.PayToAddrScript(addr)
	p := walletcore.Payment{From: a.Address, To: b.Address, Amount: 100000, Rate: 10031}
	for n := byte(1); n < 3; n++ {
		tx := wire.NewMsgTx(2)
		hash := chainhash.Hash{}
		hash[0] = n
		tx.AddTxIn(wire.NewTxIn(&wire.OutPoint{Hash: hash, Index: 1}, []byte{1, n}, nil))
		tx.AddTxOut(wire.NewTxOut(60000, script))
		var raw bytes.Buffer
		_ = tx.Serialize(&raw)
		p.UTXOs = append(p.UTXOs, walletcore.UTXO{TxID: tx.TxHash().String(), Vout: 0, Raw: hex.EncodeToString(raw.Bytes()), Confirmations: 10})
	}
	raw, _ := json.MarshalIndent(map[string]any{"payment": p, "entropy": a.Entropy}, "", "  ")
	fmt.Println(string(raw))
}
