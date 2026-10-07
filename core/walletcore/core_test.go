package walletcore

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/chaincfg/chainhash"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	bip39 "github.com/tyler-smith/go-bip39"
	"strings"
	"testing"
	"time"
)

func identityFor(t *testing.T, b []byte) Identity {
	t.Helper()
	id, e := identify(b)
	if e != nil {
		t.Fatal(e)
	}
	return id
}
func funding(t *testing.T, addr string, amount int64, n byte, coinbase bool, confirmations int64) UTXO {
	t.Helper()
	script, e := addressScript(addr)
	if e != nil {
		t.Fatal(e)
	}
	tx := wire.NewMsgTx(2)
	hash := chainhash.Hash{}
	hash[0] = n
	index := uint32(1)
	if coinbase {
		hash = chainhash.Hash{}
		index = 0xffffffff
	}
	tx.AddTxIn(wire.NewTxIn(&wire.OutPoint{Hash: hash, Index: index}, []byte{1, n}, nil))
	tx.AddTxOut(wire.NewTxOut(amount, script))
	return UTXO{tx.TxHash().String(), 0, hex.EncodeToString(serialize(tx)), confirmations}
}
func payment(t *testing.T, amounts ...int64) (Payment, []byte) {
	t.Helper()
	b := make([]byte, 32)
	own := identityFor(t, b)
	other := make([]byte, 32)
	other[0] = 1
	to := identityFor(t, other)
	p := Payment{From: own.Address, To: to.Address, Amount: 100000, Rate: 10031}
	for i, a := range amounts {
		p.UTXOs = append(p.UTXOs, funding(t, own.Address, a, byte(i+1), false, 10))
	}
	return p, b
}
func TestRecoveryVector(t *testing.T) {
	response := Execute(`{"action":"import","mnemonic":"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"}`)
	var result struct {
		Result Identity `json:"result"`
	}
	if e := json.Unmarshal([]byte(response), &result); e != nil {
		t.Fatal(e)
	}
	if result.Result.Address != "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74" || result.Result.Path != Path {
		t.Fatal("recovery vector changed")
	}
	b, e := entropy(result.Result.Entropy)
	if e != nil {
		t.Fatal(e)
	}
	defer wipe(b)
	if identityFor(t, b).Mnemonic != result.Result.Mnemonic {
		t.Fatal("backup roundtrip failed")
	}
}
func TestAllBIP39LengthsImportDeriveAndSign(t *testing.T) {
	counts := []int{12, 15, 18, 21, 24}
	lastWords := []string{"about", "address", "agent", "admit", "art"}
	for i, count := range counts {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			phrase := strings.Repeat("abandon ", count-1) + lastWords[i]
			request, _ := json.Marshal(Request{Action: "import", Mnemonic: "  " + strings.ToUpper(phrase) + "\n"})
			var imported struct {
				Result Identity `json:"result"`
				Error  string   `json:"error"`
			}
			if err := json.Unmarshal([]byte(Execute(string(request))), &imported); err != nil || imported.Error != "" {
				t.Fatalf("import failed: %v %s", err, imported.Error)
			}
			id := imported.Result
			b, err := entropy(id.Entropy)
			if err != nil {
				t.Fatal(err)
			}
			defer wipe(b)
			if len(b) != 16+i*4 || !bytes.Equal(b, make([]byte, len(b))) || id.Mnemonic != phrase || id.Path != Path {
				t.Fatal("BIP39 entropy or derivation mismatch")
			}
			// The public zero-entropy fixtures are checked against Oyster's BIP39 library.
			canonical, err := bip39.NewMnemonic(b)
			if err != nil || canonical != phrase {
				t.Fatal("fixture disagrees with official BIP39 implementation")
			}
			request, _ = json.Marshal(Request{Action: "address", Entropy: id.Entropy})
			var derived struct {
				Result Identity `json:"result"`
				Error  string   `json:"error"`
			}
			if err = json.Unmarshal([]byte(Execute(string(request))), &derived); err != nil || derived.Error != "" || derived.Result.Address != id.Address {
				t.Fatal("saved entropy cannot derive original address")
			}
			p, _ := payment(t)
			p.From = id.Address
			p.UTXOs = []UTXO{funding(t, id.Address, 200000, byte(i+1), false, 10)}
			quote, err := Plan(p)
			if err != nil {
				t.Fatal(err)
			}
			request, _ = json.Marshal(Request{Action: "sign", Entropy: id.Entropy, Quote: quote})
			var signed struct {
				Result Signed `json:"result"`
				Error  string `json:"error"`
			}
			if err = json.Unmarshal([]byte(Execute(string(request))), &signed); err != nil || signed.Error != "" {
				t.Fatalf("imported wallet cannot sign: %v %s", err, signed.Error)
			}
			tx, err := deserialize(signed.Result.Raw)
			if err != nil || tx.TxHash().String() != signed.Result.TxID || signed.Result.Fee != quote.Fee {
				t.Fatal("invalid signed transaction")
			}
		})
	}
}
func TestNonBIP39LengthsAndChecksumsRejected(t *testing.T) {
	for size := 0; size <= 36; size++ {
		if size >= 16 && size <= 32 && size%4 == 0 {
			continue
		}
		request, _ := json.Marshal(Request{Action: "address", Entropy: base64.StdEncoding.EncodeToString(make([]byte, size))})
		if !strings.Contains(Execute(string(request)), `"error"`) {
			t.Fatalf("accepted nonstandard entropy length %d", size)
		}
	}
	for count := 1; count <= 27; count++ {
		request, _ := json.Marshal(Request{Action: "import", Mnemonic: strings.TrimSpace(strings.Repeat("abandon ", count))})
		if !strings.Contains(Execute(string(request)), `"error"`) {
			t.Fatalf("accepted invalid checksum/count %d", count)
		}
	}
}
func TestGeneratedWalletsUniqueAndRecoverable(t *testing.T) {
	seen := map[string]bool{}
	for range 5 {
		var response struct {
			Result Identity `json:"result"`
		}
		if e := json.Unmarshal([]byte(Execute(`{"action":"generate"}`)), &response); e != nil {
			t.Fatal(e)
		}
		id := response.Result
		if len(strings.Fields(id.Mnemonic)) != 24 || seen[id.Address] {
			t.Fatal("invalid randomness")
		}
		seen[id.Address] = true
		request, _ := json.Marshal(Request{Action: "import", Mnemonic: id.Mnemonic})
		var restored struct {
			Result Identity `json:"result"`
		}
		_ = json.Unmarshal([]byte(Execute(string(request))), &restored)
		if restored.Result.Address != id.Address || restored.Result.Entropy != id.Entropy {
			t.Fatal("recovery mismatch")
		}
	}
}
func TestOfficialEngineVerifiesMultiInputSignatures(t *testing.T) {
	p, b := payment(t, 60000, 60000)
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	signed, e := Sign(b, q)
	if e != nil {
		t.Fatal(e)
	}
	tx, e := deserialize(signed.Raw)
	if e != nil {
		t.Fatal(e)
	}
	if len(tx.TxIn) != 2 || tx.TxHash().String() != signed.TxID || signed.VSize != q.VSize {
		t.Fatal("bad signed transaction")
	}
	if signed.Fee != (int64(signed.VSize)*p.Rate+999)/1000 {
		t.Fatal("fee differs from quote")
	}
	// Verify with an independently assembled previous-output fetcher.
	fetch := txscript.NewMultiPrevOutFetcher(nil)
	for _, u := range p.UTXOs {
		prev, _ := deserialize(u.Raw)
		h, _ := chainhash.NewHashFromStr(u.TxID)
		fetch.AddPrevOut(wire.OutPoint{Hash: *h, Index: u.Vout}, prev.TxOut[u.Vout])
	}
	verify := func(tx *wire.MsgTx) error {
		hashes := txscript.NewTxSigHashes(tx, fetch)
		for i, in := range tx.TxIn {
			out := fetch.FetchPrevOutput(in.PreviousOutPoint)
			engine, err := txscript.NewEngine(out.PkScript, tx, i, txscript.StandardVerifyFlags, nil, hashes, out.Value, fetch)
			if err != nil {
				return err
			}
			if err = engine.Execute(); err != nil {
				return err
			}
		}
		return nil
	}
	if e = verify(tx); e != nil {
		t.Fatal(e)
	}
	tx.TxOut[0].Value++
	if verify(tx) == nil {
		t.Fatal("signature accepted changed recipient amount")
	}
	tx.TxOut[0].Value--
	tx.TxIn[1].Witness[0][0] ^= 1
	if verify(tx) == nil {
		t.Fatal("signature accepted corruption")
	}
}
func TestQuoteTamperingAndExpiryRejected(t *testing.T) {
	p, b := payment(t, 200000)
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	for _, mutate := range []func(*Quote){func(q *Quote) { q.Fee++ }, func(q *Quote) { q.Change++ }, func(q *Quote) { q.Payment.Amount++ }, func(q *Quote) { q.Digest = "bad" }, func(q *Quote) { q.Expires = time.Now().Unix() - 1 }, func(q *Quote) { q.Expires = time.Now().Unix() + 1000 }} {
		copy := q
		mutate(&copy)
		if _, e = Sign(b, copy); e == nil {
			t.Fatal("accepted tampered quote")
		}
	}
	wrong := bytes.Repeat([]byte{2}, 32)
	if _, e = Sign(wrong, q); e == nil {
		t.Fatal("accepted wrong wallet key")
	}
}
func TestUntrustedInputsRejected(t *testing.T) {
	for _, test := range []string{"duplicate", "rawhash", "outindex", "foreign", "excessvalue"} {
		t.Run(test, func(t *testing.T) {
			p, _ := payment(t, 200000)
			switch test {
			case "duplicate":
				p.UTXOs = append(p.UTXOs, p.UTXOs[0])
			case "rawhash":
				p.UTXOs[0].TxID = strings.Repeat("a", 64)
			case "outindex":
				p.UTXOs[0].Vout = 8
			case "foreign":
				p.UTXOs[0] = funding(t, p.To, 200000, 8, false, 100)
			case "excessvalue":
				p.UTXOs[0] = funding(t, p.From, MaxMoney+1, 8, false, 100)
			}
			if _, e := Plan(p); e == nil {
				t.Fatal("accepted untrusted input")
			}
		})
	}
}
func TestInsufficientUnconfirmedCoinbaseDustAndRates(t *testing.T) {
	for _, test := range []string{"insufficient", "unconfirmed", "coinbase", "dust", "rate", "sameaddress"} {
		t.Run(test, func(t *testing.T) {
			p, _ := payment(t, 200000)
			switch test {
			case "insufficient":
				p.Amount = 200000
			case "unconfirmed":
				p.UTXOs[0].Confirmations = 0
			case "coinbase":
				p.UTXOs[0] = funding(t, p.From, 200000, 1, true, 99)
			case "dust":
				p.Amount = 332
			case "rate":
				p.Rate = 1000001
			case "sameaddress":
				p.To = p.From
			}
			if _, e := Plan(p); e == nil {
				t.Fatal("accepted invalid payment")
			}
		})
	}
	p, b := payment(t, 200000)
	p.UTXOs[0] = funding(t, p.From, 200000, 1, true, 100)
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = Sign(b, q); e != nil {
		t.Fatal(e)
	}
}
func TestDustChangeIncludedInDisplayedFee(t *testing.T) {
	p, b := payment(t, 100450)
	p.Rate = 1000
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	if q.Change != 0 || q.Fee != 450 {
		t.Fatalf("dust change quote: %+v", q)
	}
	signed, e := Sign(b, q)
	if e != nil {
		t.Fatal(e)
	}
	tx, _ := deserialize(signed.Raw)
	if len(tx.TxOut) != 1 {
		t.Fatal("created dust change")
	}
}
func TestMalformedMnemonicAndJSON(t *testing.T) {
	for _, input := range []string{"{", `{"action":"import","mnemonic":"hello"}`, `{"action":"identity","entropy":"AA=="}`, `{"action":"unknown"}`} {
		if !strings.Contains(Execute(input), `"error"`) {
			t.Fatal("accepted malformed request")
		}
	}
}

func TestPaymentToP2MRRecipient(t *testing.T) {
	p, b := payment(t, 200000)
	recipient, e := btcutil.NewAddressMerkleRoot(bytes.Repeat([]byte{7}, 32), &chaincfg.MainNetParams)
	if e != nil {
		t.Fatal(e)
	}
	p.To = recipient.EncodeAddress()
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	signed, e := Sign(b, q)
	if e != nil {
		t.Fatal(e)
	}
	tx, e := deserialize(signed.Raw)
	if e != nil {
		t.Fatal(e)
	}
	expected, e := txscript.PayToAddrScript(recipient)
	if e != nil {
		t.Fatal(e)
	}
	if !bytes.Equal(tx.TxOut[0].PkScript, expected) || tx.TxOut[0].Value != p.Amount {
		t.Fatal("incorrect P2MR recipient output")
	}
}

func TestMaximumSpendsAllMatureInputsAndSignsExactOneOutput(t *testing.T) {
	p, b := payment(t, 1000000, 2000000)
	p.UTXOs = append(p.UTXOs, funding(t, p.From, 9000000, 9, true, 1), funding(t, p.From, 8000000, 10, false, 0))
	q, e := PlanMaximum(p)
	if e != nil {
		t.Fatal(e)
	}
	if !q.Payment.Sweep || q.Change != 0 || q.Payment.Amount+q.Fee != 3000000 {
		t.Fatalf("maximum quote is not exact: %+v", q)
	}
	signed, e := Sign(b, q)
	if e != nil {
		t.Fatal(e)
	}
	tx, e := deserialize(signed.Raw)
	if e != nil {
		t.Fatal(e)
	}
	if len(tx.TxIn) != 2 || len(tx.TxOut) != 1 || tx.TxOut[0].Value != q.Payment.Amount || signed.Fee != q.Fee {
		t.Fatal("sweep transaction shape/amount differs")
	}
	q.Payment.Amount--
	if _, e = Sign(b, q); e == nil {
		t.Fatal("accepted changed sweep amount")
	}
}
func TestMaximumRejectsInvalidInputsAndUnpayableFees(t *testing.T) {
	p, _ := payment(t, 100)
	if _, e := PlanMaximum(p); e == nil {
		t.Fatal("accepted insufficient sweep balance")
	}
	p, _ = payment(t, 1000000)
	p.UTXOs = append(p.UTXOs, p.UTXOs[0])
	if _, e := PlanMaximum(p); e == nil {
		t.Fatal("accepted duplicate sweep inputs")
	}
	p, _ = payment(t, 1000000)
	p.UTXOs[0].Confirmations = 0
	if _, e := PlanMaximum(p); e == nil {
		t.Fatal("swept unconfirmed input")
	}
	amounts := make([]int64, 101)
	for i := range amounts {
		amounts[i] = 1000000
	}
	p, _ = payment(t, amounts...)
	if _, e := PlanMaximum(p); e == nil {
		t.Fatal("silently omitted sweep inputs beyond limit")
	}
	p, _ = payment(t, 1000000)
	p.UTXOs[0].Raw = "00"
	if _, e := PlanMaximum(p); e == nil {
		t.Fatal("accepted malformed provenance")
	}
}
