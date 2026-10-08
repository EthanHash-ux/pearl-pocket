package inference

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/chaincfg/chainhash"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"pearlwallet/walletcore"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Public deterministic test vector. This key must never receive real funds.
func testConfig(t *testing.T) Config {
	t.Helper()
	k, e := hdkeychain.NewMaster(bytes.Repeat([]byte{0x42}, 32), &chaincfg.MainNetParams)
	if e != nil {
		t.Fatal(e)
	}
	for _, i := range []uint32{86 + 1<<31, 808276 + 1<<31, 1 << 31} {
		c, e := k.DeriveNonStandard(i)
		k.Zero()
		if e != nil {
			t.Fatal(e)
		}
		k = c
	}
	defer k.Zero()
	pub, e := k.Neuter()
	if e != nil {
		t.Fatal(e)
	}
	return Config{Merchant: "Test merchant (no real funds)", Xpub: pub.String(), Models: []Model{{"test/model", 10000, 256, 4000}}}
}

type testChain struct{ fail bool }

func (c *testChain) Height(context.Context) (int64, error) { return 100, nil }
func (c *testChain) Verify(context.Context, Invoice, string) (Proof, error) {
	if c.fail {
		return Proof{}, errors.New("needs 6 confirmations")
	}
	return Proof{strings.Repeat("b", 64), 101}, nil
}
func api(t *testing.T, g http.Handler, method, path, key string, v any, headers map[string]string) (int, map[string]any) {
	t.Helper()
	var b []byte
	if v != nil {
		b, _ = json.Marshal(v)
	}
	r := httptest.NewRequest(method, path, bytes.NewReader(b))
	if key != "" {
		r.Header.Set("Authorization", "Bearer "+key)
	}
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	var out map[string]any
	if e := json.Unmarshal(w.Body.Bytes(), &out); e != nil {
		t.Fatalf("invalid JSON %s", w.Body.String())
	}
	return w.Code, out
}
func setup(t *testing.T, upstream http.Handler) (*Gateway, string, string, *testChain) {
	t.Helper()
	s := httptest.NewTLSServer(upstream)
	t.Cleanup(s.Close)
	chain := &testChain{}
	g, e := Open(filepath.Join(t.TempDir(), "ledger.json"), testConfig(t), chain, s.URL, "test-upstream-key", "test-invite-code-12345")
	if e != nil {
		t.Fatal(e)
	}
	g.client = s.Client()
	g.now = func() time.Time { return time.Unix(1000, 0) }
	status, out := api(t, g, "POST", "/v1/accounts", "", map[string]string{"signup_code": "test-invite-code-12345"}, nil)
	if status != 201 {
		t.Fatal(out)
	}
	return g, out["api_key"].(string), s.URL, chain
}
func create(t *testing.T, g *Gateway, key string) string {
	t.Helper()
	status, out := api(t, g, "POST", "/v1/invoices", key, map[string]any{"model": "test/model", "request_count": 2}, nil)
	if status != 201 {
		t.Fatal(out)
	}
	return out["id"].(string)
}
func success(w http.ResponseWriter, r *http.Request) {
	reply(w, 200, map[string]any{"id": "completion-1", "object": "chat.completion", "choices": []any{map[string]any{"message": map[string]string{"content": "test answer"}}}, "usage": map[string]int{"prompt_tokens": 10, "completion_tokens": 20}})
}
func chatBody() map[string]any {
	return map[string]any{"model": "test/model", "messages": []any{map[string]string{"role": "user", "content": "hello"}}, "max_tokens": 256, "stream": false}
}

func TestInvoiceOwnershipUniqueAddressesAndPaymentReplay(t *testing.T) {
	g, key, _, chain := setup(t, http.HandlerFunc(success))
	a, b := create(t, g, key), create(t, g, key)
	if g.data.Invoices[a].Invoice.Address == g.data.Invoices[b].Invoice.Address {
		t.Fatal("reused recipient")
	}
	status, _ := api(t, g, "POST", "/v1/accounts", "", map[string]string{"signup_code": "wrong"}, nil)
	if status != 403 {
		t.Fatal(status)
	}
	status, _ = api(t, g, "GET", "/v1/invoices/"+a, "prlai_"+strings.Repeat("0", 64), nil, nil)
	if status != 401 {
		t.Fatal(status)
	}
	_, other := api(t, g, "POST", "/v1/accounts", "", map[string]string{"signup_code": "test-invite-code-12345"}, nil)
	status, _ = api(t, g, "GET", "/v1/invoices/"+a, other["api_key"].(string), nil, nil)
	if status != 404 {
		t.Fatal(status)
	}
	tx := strings.Repeat("a", 64)
	chain.fail = true
	status, _ = api(t, g, "POST", "/v1/invoices/"+a+"/claim", key, map[string]string{"txid": tx}, nil)
	if status != 409 || g.data.Invoices[a].Invoice.Remaining != 0 {
		t.Fatal("credited immature payment")
	}
	chain.fail = false
	for i := 0; i < 3; i++ {
		status, _ = api(t, g, "POST", "/v1/invoices/"+a+"/claim", key, map[string]string{"txid": tx}, nil)
		if status != 200 || g.data.Invoices[a].Invoice.Remaining != 2 {
			t.Fatal("claim not idempotent")
		}
	}
	status, _ = api(t, g, "POST", "/v1/invoices/"+b+"/claim", key, map[string]string{"txid": tx}, nil)
	if status != 409 {
		t.Fatal("double credited transaction")
	}
	restarted, e := Open(g.file, g.config, g.chain, g.upstream, g.key, g.invite)
	if e != nil {
		t.Fatal(e)
	}
	if restarted.data.Invoices[a].Invoice.Remaining != 2 {
		t.Fatal("payment lost after restart")
	}
	if len(restarted.data.Transactions) != 1 {
		t.Fatal("payment replay protection lost")
	}
	changed := g.config
	changed.Xpub = testConfig(t).Xpub + "bad"
	if _, e = Open(g.file, changed, g.chain, g.upstream, g.key, g.invite); e == nil {
		t.Fatal("accepted xpub change")
	}
}
func TestConcurrentInferenceReservesOnceAndSurvivesRestart(t *testing.T) {
	var posts atomic.Int32
	g, key, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		posts.Add(1)
		if r.Header.Get("Authorization") != "Bearer test-upstream-key" {
			t.Error("wrong upstream key")
		}
		success(w, r)
	}))
	inv := create(t, g, key)
	api(t, g, "POST", "/v1/invoices/"+inv+"/claim", key, map[string]string{"txid": strings.Repeat("a", 64)}, nil)
	headers := map[string]string{"X-Pearl-Invoice": inv, "Idempotency-Key": "unique-request-0001"}
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			code, _ := api(t, g, "POST", "/v1/chat/completions", key, chatBody(), headers)
			if code != 200 && code != 409 {
				t.Errorf("code %d", code)
			}
		}()
	}
	group.Wait()
	if posts.Load() != 1 || g.data.Invoices[inv].Invoice.Remaining != 1 {
		t.Fatal("request replay consumed multiple calls")
	}
	restarted, e := Open(g.file, g.config, g.chain, g.upstream, g.key, g.invite)
	if e != nil {
		t.Fatal(e)
	}
	restarted.client = g.client
	code, result := api(t, restarted, "POST", "/v1/chat/completions", key, chatBody(), headers)
	if code != 200 || result["id"] != "completion-1" || posts.Load() != 1 {
		t.Fatal("replay after restart reached upstream")
	}
	body := chatBody()
	body["max_tokens"] = 100
	code, _ = api(t, restarted, "POST", "/v1/chat/completions", key, body, headers)
	if code != 409 {
		t.Fatal("same ID with changed body accepted")
	}
	headers["Idempotency-Key"] = "unique-request-0002"
	code, _ = api(t, restarted, "POST", "/v1/chat/completions", key, chatBody(), headers)
	if code != 200 {
		t.Fatal(code)
	}
	headers["Idempotency-Key"] = "unique-request-0003"
	code, _ = api(t, restarted, "POST", "/v1/chat/completions", key, chatBody(), headers)
	if code != 402 || posts.Load() != 2 {
		t.Fatal("allowed exhausted credit")
	}
}
func TestUnknownUpstreamResultIsNeverAutomaticallyRepeated(t *testing.T) {
	var posts atomic.Int32
	g, key, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		posts.Add(1)
		failure(w, 503, "mock lost reply after processing")
	}))
	inv := create(t, g, key)
	api(t, g, "POST", "/v1/invoices/"+inv+"/claim", key, map[string]string{"txid": strings.Repeat("a", 64)}, nil)
	h := map[string]string{"X-Pearl-Invoice": inv, "Idempotency-Key": "ambiguous-request-0001"}
	code, _ := api(t, g, "POST", "/v1/chat/completions", key, chatBody(), h)
	if code != 502 {
		t.Fatal(code)
	}
	code, _ = api(t, g, "POST", "/v1/chat/completions", key, chatBody(), h)
	if code != 409 || posts.Load() != 1 {
		t.Fatal("unsafe upstream retry")
	}
	code, out := api(t, g, "GET", "/v1/requests/ambiguous-request-0001", key, nil, nil)
	if code != 200 || out["status"] != "unknown" || g.data.Invoices[inv].Invoice.Remaining != 1 {
		t.Fatal("unknown reservation lost")
	}
	h["Idempotency-Key"] = "ambiguous-request-0002"
	code, _ = api(t, g, "POST", "/v1/chat/completions", key, chatBody(), h)
	if code != 409 || posts.Load() != 1 {
		t.Fatal("new ID bypassed ambiguous request")
	}
	if e := g.Reconcile(digest(key), "ambiguous-request-0001", "provider-confirmed-no-inference-test", nil, true); e != nil {
		t.Fatal(e)
	}
	if g.data.Invoices[inv].Invoice.Remaining != 2 {
		t.Fatal("failed to refund known failure")
	}
	if e := g.Reconcile(digest(key), "ambiguous-request-0001", "provider-confirmed-no-inference-test", nil, true); e == nil {
		t.Fatal("double refunded")
	}
}
func TestInputLimitsAndNoArbitraryUpstreamParameters(t *testing.T) {
	var posts atomic.Int32
	g, key, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { posts.Add(1); success(w, r) }))
	inv := create(t, g, key)
	api(t, g, "POST", "/v1/invoices/"+inv+"/claim", key, map[string]string{"txid": strings.Repeat("a", 64)}, nil)
	h := map[string]string{"X-Pearl-Invoice": inv, "Idempotency-Key": "invalid-request-0001"}
	for _, change := range []func(map[string]any){func(b map[string]any) { b["max_tokens"] = 257 }, func(b map[string]any) { b["stream"] = true }, func(b map[string]any) { b["model"] = "unpaid/model" }, func(b map[string]any) { b["tools"] = []any{} }, func(b map[string]any) {
		b["messages"] = []any{map[string]string{"role": "user", "content": strings.Repeat("中", 2000)}}
	}} {
		b := chatBody()
		change(b)
		code, _ := api(t, g, "POST", "/v1/chat/completions", key, b, h)
		if code != 400 {
			t.Fatal(code)
		}
	}
	if posts.Load() != 0 || g.data.Invoices[inv].Invoice.Remaining != 2 {
		t.Fatal("invalid request consumed credit")
	}
}
func TestBlockbookRawAmountCanonicalBlockAndInvoiceWindow(t *testing.T) {
	cfg := testConfig(t)
	address, e := walletcore.InvoiceAddress(cfg.Xpub, 1)
	if e != nil {
		t.Fatal(e)
	}
	a, _ := btcutil.DecodeAddress(address, &chaincfg.MainNetParams)
	script, _ := txscript.PayToAddrScript(a)
	tx := wire.NewMsgTx(2)
	tx.AddTxIn(wire.NewTxIn(&wire.OutPoint{Hash: chainhash.Hash{1}, Index: 0}, nil, nil))
	tx.AddTxOut(wire.NewTxOut(20000, script))
	var raw bytes.Buffer
	tx.Serialize(&raw)
	id := tx.TxHash().String()
	hash := strings.Repeat("b", 64)
	inv := Invoice{Address: address, Amount: 20000, Height: 100, Created: 1000, Expires: 1900}
	for _, test := range []struct {
		name               string
		height, time, conf int64
		canonical, raw, id string
		amount             int64
		pass               bool
	}{
		{"valid", 101, 1100, 6, hash, hex.EncodeToString(raw.Bytes()), id, 20000, true},
		{"five-confirmations", 101, 1100, 5, hash, hex.EncodeToString(raw.Bytes()), id, 20000, false},
		{"old-height", 100, 1100, 6, hash, hex.EncodeToString(raw.Bytes()), id, 20000, false},
		{"late-payment", 101, 1901, 6, hash, hex.EncodeToString(raw.Bytes()), id, 20000, false},
		{"reorg", 101, 1100, 6, strings.Repeat("c", 64), hex.EncodeToString(raw.Bytes()), id, 20000, false},
		{"underpaid", 101, 1100, 6, hash, hex.EncodeToString(raw.Bytes()), id, 20001, false},
		{"overpaid", 101, 1100, 6, hash, hex.EncodeToString(raw.Bytes()), id, 19999, false},
		{"raw-txid-mismatch", 101, 1100, 6, hash, hex.EncodeToString(raw.Bytes()), strings.Repeat("a", 64), 20000, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/":
					reply(w, 200, map[string]any{"blockbook": map[string]any{"coin": "Pearl", "inSync": true, "decimals": 8}, "backend": map[string]any{"chain": "mainnet", "blocks": 106}})
				case "/tx/" + test.id:
					reply(w, 200, map[string]any{"txid": test.id, "hex": test.raw, "blockHash": hash, "blockHeight": test.height, "blockTime": test.time, "confirmations": test.conf})
				case fmt.Sprintf("/block-index/%d", test.height):
					reply(w, 200, map[string]string{"blockHash": test.canonical})
				default:
					http.NotFound(w, r)
				}
			}))
			defer s.Close()
			b, e := NewBlockbook(s.URL)
			if e != nil {
				t.Fatal(e)
			}
			b.Client = s.Client()
			v := inv
			v.Amount = test.amount
			p, e := b.Verify(context.Background(), v, test.id)
			if (e == nil) != test.pass {
				t.Fatalf("proof %v error %v", p, e)
			}
		})
	}
}
