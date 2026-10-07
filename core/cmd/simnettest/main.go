// Runs a complete transfer against an isolated local official Pearl simnet node.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/wire"
	"math/big"
	"net/http"
	"os"
	"os/exec"
	"pearlwallet/walletcore"
	"time"
)

func rpc(method string, params ...any) json.RawMessage {
	if params == nil {
		params = []any{}
	}
	b, _ := json.Marshal(map[string]any{"jsonrpc": "1.0", "id": 1, "method": method, "params": params})
	req, _ := http.NewRequest("POST", "http://127.0.0.1:18567", bytes.NewReader(b))
	req.SetBasicAuth("wallet-simnet-test", "local-test-only")
	req.Header.Set("Content-Type", "application/json")
	client := http.Client{Timeout: 30 * time.Second}
	response, e := client.Do(req)
	if e != nil {
		panic(e)
	}
	defer response.Body.Close()
	var result struct {
		Result json.RawMessage `json:"result"`
		Error  any             `json:"error"`
	}
	if e = json.NewDecoder(response.Body).Decode(&result); e != nil {
		panic(e)
	}
	if result.Error != nil {
		panic(fmt.Sprint(result.Error))
	}
	return result.Result
}
func identity(b []byte) walletcore.Identity {
	req, _ := json.Marshal(map[string]string{"action": "identity", "entropy": base64.StdEncoding.EncodeToString(b)})
	var r struct {
		Result walletcore.Identity `json:"result"`
	}
	_ = json.Unmarshal([]byte(walletcore.Execute(string(req))), &r)
	return r.Result
}
func main() {
	binary := os.Getenv("PEARL_NODE")
	if binary == "" {
		binary = "/opt/pearl-build-tools/pearld-regtest"
	}
	dir, e := os.MkdirTemp("", "pearl-simnet-wallet-")
	if e != nil {
		panic(e)
	}
	defer os.RemoveAll(dir)
	entropy := make([]byte, 32)
	a := identity(entropy)
	other := make([]byte, 32)
	other[0] = 1
	b := identity(other)
	addr, e := btcutil.DecodeAddress(a.Address, &chaincfg.MainNetParams)
	if e != nil {
		panic(e)
	}
	sim, e := btcutil.NewAddressTaproot(addr.ScriptAddress(), &chaincfg.SimNetParams)
	if e != nil {
		panic(e)
	}
	cmd := exec.Command(binary, "--simnet", "--datadir="+dir, "--logdir="+dir+"/logs", "--nodnsseed", "--nolisten", "--notls", "--rpclisten=127.0.0.1:18567", "--rpcuser=wallet-simnet-test", "--rpcpass=local-test-only", "--txindex", "--miningaddr="+sim.EncodeAddress())
	log, e := os.Create(dir + "/node.log")
	if e != nil {
		panic(e)
	}
	defer log.Close()
	cmd.Stdout = log
	cmd.Stderr = log
	if e = cmd.Start(); e != nil {
		panic(e)
	}
	defer func() { _ = cmd.Process.Signal(os.Interrupt); _ = cmd.Wait() }()
	time.Sleep(2 * time.Second)
	var hashes []string
	_ = json.Unmarshal(rpc("generate", 101), &hashes)
	if len(hashes) != 101 {
		panic("blocks not generated")
	}
	var block struct {
		Tx []string `json:"tx"`
	}
	_ = json.Unmarshal(rpc("getblock", hashes[0], 1), &block)
	if len(block.Tx) == 0 {
		panic("coinbase missing")
	}
	var raw string
	_ = json.Unmarshal(rpc("getrawtransaction", block.Tx[0], 0), &raw)
	prev := wire.NewMsgTx(2)
	encoded, _ := hex.DecodeString(raw)
	if e = prev.Deserialize(bytes.NewReader(encoded)); e != nil {
		panic(e)
	}
	p := walletcore.Payment{From: a.Address, To: b.Address, Amount: 100000, Rate: 10031, UTXOs: []walletcore.UTXO{{TxID: block.Tx[0], Vout: 0, Raw: raw, Confirmations: 101}}}
	q, e := walletcore.Plan(p)
	if e != nil {
		panic(e)
	}
	signed, e := walletcore.Sign(entropy, q)
	if e != nil {
		panic(e)
	}
	var accepted string
	_ = json.Unmarshal(rpc("sendrawtransaction", signed.Raw), &accepted)
	if accepted != signed.TxID {
		panic("broadcast ID mismatch")
	}
	var confirmed []string
	_ = json.Unmarshal(rpc("generate", 1), &confirmed)
	var output struct {
		Confirmations int         `json:"confirmations"`
		Value         json.Number `json:"value"`
	}
	_ = json.Unmarshal(rpc("gettxout", signed.TxID, 0, true), &output)
	if output.Confirmations != 1 || string(output.Value) != "0.001" {
		panic(fmt.Sprintf("recipient output not confirmed: %+v", output))
	}
	sweepPayment := walletcore.Payment{From: a.Address, To: b.Address, Rate: 10031, UTXOs: []walletcore.UTXO{{TxID: signed.TxID, Vout: 1, Raw: signed.Raw, Confirmations: 1}}}
	maximum, e := walletcore.PlanMaximum(sweepPayment)
	if e != nil {
		panic(e)
	}
	swept, e := walletcore.Sign(entropy, maximum)
	if e != nil {
		panic(e)
	}
	_ = json.Unmarshal(rpc("sendrawtransaction", swept.Raw), &accepted)
	if accepted != swept.TxID {
		panic("maximum broadcast ID mismatch")
	}
	_ = json.Unmarshal(rpc("generate", 1), &confirmed)
	_ = json.Unmarshal(rpc("gettxout", swept.TxID, 0, true), &output)
	expected := new(big.Rat).SetFrac(big.NewInt(maximum.Payment.Amount), big.NewInt(100000000))
	actual, ok := new(big.Rat).SetString(string(output.Value))
	if !ok || output.Confirmations != 1 || actual.Cmp(expected) != 0 {
		panic("maximum recipient output not confirmed")
	}
	decodedSweep := wire.NewMsgTx(2)
	sweepBytes, _ := hex.DecodeString(swept.Raw)
	_ = decodedSweep.Deserialize(bytes.NewReader(sweepBytes))
	if len(decodedSweep.TxOut) != 1 || maximum.Change != 0 {
		panic("maximum generated change")
	}
	result := map[string]any{"result": "PASS", "network": "isolated official Pearl simnet", "upstream_commit": "2f8b770cac8f8b74be05c1fc51c2baeb76ce0701", "txid": signed.TxID, "amount_grains": p.Amount, "fee_grains": signed.Fee, "confirmations": output.Confirmations, "checked": []string{"101 coinbase maturity blocks", "local official signing", "node sendrawtransaction acceptance", "block confirmation", "recipient unspent output amount"}, "mainnet_funds_used": false, "maximum_transfer": map[string]any{"result": "PASS", "txid": swept.TxID, "amount_grains": maximum.Payment.Amount, "fee_grains": swept.Fee, "confirmations": output.Confirmations, "outputs": len(decodedSweep.TxOut), "change_grains": maximum.Change}}
	encoded, _ = json.MarshalIndent(result, "", "  ")
	fmt.Println(string(encoded))
}
