// Package inference implements a small, single-process PRL-funded API merchant.
// It never has wallet private keys and never submits blockchain transactions.
package inference

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"github.com/pearl-research-labs/pearl/node/wire"
	"pearlwallet/walletcore"
)

type Model struct {
	ID        string `json:"id"`
	Price     int64  `json:"price_per_request_units,string"`
	MaxTokens int    `json:"max_tokens"`
	MaxInput  int    `json:"max_input_bytes"`
}
type Config struct {
	Merchant string  `json:"merchant"`
	Xpub     string  `json:"account_xpub"`
	Models   []Model `json:"models"`
}
type Invoice struct {
	ID          string `json:"id"`
	Owner       string `json:"-"`
	Index       uint32 `json:"index"`
	Address     string `json:"address"`
	Model       Model  `json:"model"`
	Count       int    `json:"request_count"`
	Amount      int64  `json:"amount_units,string"`
	Created     int64  `json:"created_at"`
	Expires     int64  `json:"expires_at"`
	Height      int64  `json:"created_height"`
	TxID        string `json:"txid,omitempty"`
	BlockHash   string `json:"block_hash,omitempty"`
	BlockHeight int64  `json:"block_height,omitempty"`
	Remaining   int    `json:"remaining_requests"`
	Status      string `json:"status"`
}

// Separate disk representation keeps account ownership out of public responses.
type savedInvoice struct {
	Invoice Invoice `json:"invoice"`
	Owner   string  `json:"owner"`
}
type Call struct {
	Hash           string          `json:"hash"`
	Invoice        string          `json:"invoice"`
	Status         string          `json:"status"`
	Response       json.RawMessage `json:"response,omitempty"`
	Reconciliation string          `json:"reconciliation,omitempty"`
}
type ledger struct {
	Xpub         string                  `json:"xpub"`
	Next         uint32                  `json:"next_index"`
	Accounts     map[string]bool         `json:"accounts"`
	Invoices     map[string]savedInvoice `json:"invoices"`
	Transactions map[string]string       `json:"transactions"`
	Calls        map[string]Call         `json:"calls"`
}
type Proof struct {
	Hash   string
	Height int64
}
type Chain interface {
	Height(context.Context) (int64, error)
	Verify(context.Context, Invoice, string) (Proof, error)
}
type Gateway struct {
	mu                    sync.Mutex
	data                  ledger
	file                  string
	dead                  bool
	config                Config
	chain                 Chain
	upstream, key, invite string
	client                *http.Client
	now                   func() time.Time
}

var idPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{16,80}$`)
var txPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func secureURL(s string) bool {
	u, e := url.Parse(s)
	return e == nil && u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.RawQuery == "" && u.Fragment == ""
}
func Open(file string, cfg Config, chain Chain, upstream, key, invite string) (*Gateway, error) {
	if cfg.Merchant == "" || len(cfg.Merchant) > 100 || len(cfg.Models) == 0 || len(cfg.Models) > 30 || !secureURL(upstream) || len(key) < 10 || len(invite) < 16 || chain == nil {
		return nil, errors.New("merchant, models, HTTPS upstream, API key and 16+ character signup code required")
	}
	if _, e := walletcore.InvoiceAddress(cfg.Xpub, 1); e != nil {
		return nil, e
	}
	seen := map[string]bool{}
	for _, m := range cfg.Models {
		if m.ID == "" || len(m.ID) > 150 || seen[m.ID] || m.Price < walletcore.Dust || m.Price > 100_000_000 || m.MaxTokens < 1 || m.MaxTokens > 4096 || m.MaxInput < 1 || m.MaxInput > 16_384 {
			return nil, errors.New("invalid model tariff or limits")
		}
		seen[m.ID] = true
	}
	g := &Gateway{file: file, config: cfg, chain: chain, upstream: strings.TrimRight(upstream, "/"), key: key, invite: invite, now: time.Now,
		client: &http.Client{Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		data:   ledger{Xpub: cfg.Xpub, Next: 1, Accounts: map[string]bool{}, Invoices: map[string]savedInvoice{}, Transactions: map[string]string{}, Calls: map[string]Call{}}}
	if b, e := os.ReadFile(file); e == nil {
		if len(b) > 80_000_000 {
			return nil, errors.New("ledger exceeds pilot limit")
		}
		if e = json.Unmarshal(b, &g.data); e != nil {
			return nil, e
		}
		if g.data.Xpub != cfg.Xpub || g.data.Next < 1 || g.data.Next >= 1<<31 || g.data.Accounts == nil || g.data.Invoices == nil || g.data.Calls == nil || g.data.Transactions == nil {
			return nil, errors.New("invalid ledger or merchant xpub changed")
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	return g, nil
}

// All mutations occur under mu and are fsynced before response or upstream call.
// A disk error closes the process to further work; ambiguous persistence cannot
// result in a second payment credit or a second inference call.
func (g *Gateway) save() error {
	if g.dead {
		return errors.New("ledger unavailable")
	}
	b, e := json.Marshal(g.data)
	if e != nil || len(b) > 80_000_000 {
		g.dead = true
		return errors.New("ledger serialization failed or pilot capacity exceeded")
	}
	dir := filepath.Dir(g.file)
	if e = os.MkdirAll(dir, 0700); e != nil {
		g.dead = true
		return e
	}
	f, e := os.CreateTemp(dir, ".ledger-")
	if e != nil {
		g.dead = true
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(b)
	}
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e == nil {
		e = os.Rename(name, g.file)
	}
	if e == nil {
		var d *os.File
		d, e = os.Open(dir)
		if e == nil {
			e = d.Sync()
			d.Close()
		}
	}
	if e != nil {
		g.dead = true
	}
	return e
}
func token() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func digest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func reply(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
func failure(w http.ResponseWriter, status int, message string) {
	reply(w, status, map[string]any{"error": map[string]string{"message": message}})
}
func body(w http.ResponseWriter, r *http.Request, v any) error {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 24_000))
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		return e
	}
	var rest any
	if d.Decode(&rest) != io.EOF {
		return errors.New("unexpected body suffix")
	}
	return nil
}
func (g *Gateway) account(r *http.Request) string {
	a := r.Header.Get("Authorization")
	if !strings.HasPrefix(a, "Bearer prlai_") {
		return ""
	}
	t := strings.TrimPrefix(a, "Bearer ")
	if len(t) != 70 {
		return ""
	}
	h := digest(t)
	if !g.data.Accounts[h] {
		return ""
	}
	return h
}
func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Method == "GET" && r.URL.Path == "/v1/catalog" {
		reply(w, 200, map[string]any{"protocol": "pearl-inference-1", "network": "pearl-mainnet", "asset": "PRL", "decimals": 8, "merchant": g.config.Merchant, "account_xpub": g.config.Xpub, "confirmations": 6, "models": g.config.Models})
		return
	}
	if r.Method == "POST" && r.URL.Path == "/v1/accounts" {
		var b struct {
			Code string `json:"signup_code"`
		}
		if body(w, r, &b) != nil || subtle.ConstantTimeCompare([]byte(digest(b.Code)), []byte(digest(g.invite))) != 1 {
			failure(w, 403, "invalid signup code")
			return
		}
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.dead || len(g.data.Accounts) >= 1000 {
			failure(w, 503, "account capacity reached")
			return
		}
		t := "prlai_" + token()
		g.data.Accounts[digest(t)] = true
		if g.save() != nil {
			failure(w, 503, "ledger unavailable")
			return
		}
		reply(w, 201, map[string]string{"api_key": t})
		return
	}
	g.mu.Lock()
	owner := g.account(r)
	dead := g.dead
	g.mu.Unlock()
	if dead {
		failure(w, 503, "ledger unavailable")
		return
	}
	if owner == "" {
		failure(w, 401, "API key required")
		return
	}
	if r.Method == "POST" && r.URL.Path == "/v1/invoices" {
		g.createInvoice(w, r, owner)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/invoices/") {
		g.invoice(w, r, owner)
		return
	}
	if r.Method == "POST" && r.URL.Path == "/v1/chat/completions" {
		g.chat(w, r, owner)
		return
	}
	if r.Method == "GET" && r.URL.Path == "/v1/models" {
		rows := []map[string]string{}
		for _, m := range g.config.Models {
			rows = append(rows, map[string]string{"id": m.ID, "object": "model", "owned_by": g.config.Merchant})
		}
		reply(w, 200, map[string]any{"object": "list", "data": rows})
		return
	}
	if r.Method == "GET" && strings.HasPrefix(r.URL.Path, "/v1/requests/") {
		id := strings.TrimPrefix(r.URL.Path, "/v1/requests/")
		if !idPattern.MatchString(id) {
			failure(w, 400, "invalid request id")
			return
		}
		g.mu.Lock()
		c, ok := g.data.Calls[owner+":"+id]
		g.mu.Unlock()
		if !ok {
			failure(w, 404, "request not found")
			return
		}
		reply(w, 200, c)
		return
	}
	failure(w, 404, "endpoint not found")
}
func (g *Gateway) createInvoice(w http.ResponseWriter, r *http.Request, owner string) {
	var b struct {
		Model string `json:"model"`
		Count int    `json:"request_count"`
	}
	if body(w, r, &b) != nil || b.Count < 1 || b.Count > 1000 {
		failure(w, 400, "invalid request count")
		return
	}
	var m Model
	for _, v := range g.config.Models {
		if v.ID == b.Model {
			m = v
		}
	}
	if m.ID == "" {
		failure(w, 400, "unknown model")
		return
	}
	height, e := g.chain.Height(r.Context())
	if e != nil {
		failure(w, 503, "Pearl index unavailable")
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.dead || len(g.data.Invoices) >= 5000 {
		failure(w, 503, "invoice capacity reached")
		return
	}
	outstanding := 0
	for _, v := range g.data.Invoices {
		if v.Owner == owner && v.Invoice.Status == "unpaid" && v.Invoice.Expires > g.now().Unix() {
			outstanding++
		}
	}
	if outstanding >= 10 {
		failure(w, 409, "too many unpaid invoices")
		return
	}
	index := g.data.Next
	address, e := walletcore.InvoiceAddress(g.config.Xpub, index)
	if e != nil {
		failure(w, 503, "address allocation failed")
		return
	}
	now := g.now().Unix()
	inv := Invoice{ID: token(), Owner: owner, Index: index, Address: address, Model: m, Count: b.Count, Amount: m.Price * int64(b.Count), Created: now, Expires: now + 900, Height: height, Status: "unpaid"}
	g.data.Next++
	g.data.Invoices[inv.ID] = savedInvoice{inv, owner}
	if g.save() != nil {
		failure(w, 503, "ledger unavailable")
		return
	}
	reply(w, 201, inv)
}
func (g *Gateway) invoice(w http.ResponseWriter, r *http.Request, owner string) {
	path := strings.TrimPrefix(r.URL.Path, "/v1/invoices/")
	claim := strings.HasSuffix(path, "/claim")
	id := strings.TrimSuffix(path, "/claim")
	if !txPattern.MatchString(id) {
		failure(w, 404, "invoice not found")
		return
	}
	g.mu.Lock()
	saved, ok := g.data.Invoices[id]
	g.mu.Unlock()
	if !ok || saved.Owner != owner {
		failure(w, 404, "invoice not found")
		return
	}
	inv := saved.Invoice
	if r.Method == "GET" && !claim {
		reply(w, 200, inv)
		return
	}
	if r.Method != "POST" || !claim {
		failure(w, 405, "method not allowed")
		return
	}
	var b struct {
		TxID string `json:"txid"`
	}
	if body(w, r, &b) != nil || !txPattern.MatchString(b.TxID) {
		failure(w, 400, "invalid transaction id")
		return
	}
	if inv.Status == "paid" {
		if inv.TxID != b.TxID {
			failure(w, 409, "invoice already paid with another transaction")
			return
		}
		reply(w, 200, inv)
		return
	}
	p, e := g.chain.Verify(r.Context(), inv, b.TxID)
	if e != nil {
		failure(w, 409, "payment not verified: "+e.Error())
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	inv = g.data.Invoices[id].Invoice
	if inv.Status == "paid" {
		if inv.TxID != b.TxID {
			failure(w, 409, "invoice already paid")
			return
		}
		reply(w, 200, inv)
		return
	}
	if _, used := g.data.Transactions[b.TxID]; used {
		failure(w, 409, "transaction already credited")
		return
	}
	inv.Status = "paid"
	inv.TxID = b.TxID
	inv.BlockHash = p.Hash
	inv.BlockHeight = p.Height
	inv.Remaining = inv.Count
	g.data.Invoices[id] = savedInvoice{inv, owner}
	g.data.Transactions[b.TxID] = id
	if g.save() != nil {
		failure(w, 503, "ledger unavailable")
		return
	}
	reply(w, 200, inv)
}
func (g *Gateway) chat(w http.ResponseWriter, r *http.Request, owner string) {
	id := r.Header.Get("Idempotency-Key")
	invoice := r.Header.Get("X-Pearl-Invoice")
	if !idPattern.MatchString(id) || !txPattern.MatchString(invoice) {
		failure(w, 400, "Idempotency-Key and X-Pearl-Invoice required")
		return
	}
	var b struct {
		Model    string `json:"model"`
		Messages []struct {
			Role    string `json:"role"`
			Content string `json:"content"`
		} `json:"messages"`
		MaxTokens int  `json:"max_tokens"`
		Stream    bool `json:"stream"`
	}
	if body(w, r, &b) != nil || b.Stream || len(b.Messages) < 1 || len(b.Messages) > 32 {
		failure(w, 400, "text chat only; streaming unsupported")
		return
	}
	input := 0
	for _, m := range b.Messages {
		if m.Role != "system" && m.Role != "user" && m.Role != "assistant" {
			failure(w, 400, "unsupported message role")
			return
		}
		input += len(m.Content)
	}
	key := owner + ":" + id
	encoded, _ := json.Marshal(b)
	hash := digest(invoice + string(encoded))
	g.mu.Lock()
	saved, ok := g.data.Invoices[invoice]
	inv := saved.Invoice
	if !ok || saved.Owner != owner || inv.Status != "paid" || inv.Model.ID != b.Model || input > inv.Model.MaxInput || b.MaxTokens < 1 || b.MaxTokens > inv.Model.MaxTokens {
		g.mu.Unlock()
		failure(w, 400, "invoice/model/input/output limits do not match")
		return
	}
	if c, exists := g.data.Calls[key]; exists {
		g.mu.Unlock()
		if c.Hash != hash {
			failure(w, 409, "request id reused with another body")
			return
		}
		if c.Status == "complete" {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(c.Response)
			return
		}
		failure(w, 409, "request outcome pending or unknown; query status, do not issue another id")
		return
	}
	if inv.Remaining <= 0 {
		g.mu.Unlock()
		failure(w, 402, "PRL-funded request quota exhausted")
		return
	}
	for k, c := range g.data.Calls {
		if strings.HasPrefix(k, owner+":") && c.Invoice == invoice && (c.Status == "pending" || c.Status == "unknown") {
			g.mu.Unlock()
			failure(w, 409, "resolve the prior unknown request before making a new call on this invoice")
			return
		}
	}
	if g.dead || len(g.data.Calls) >= 1000 {
		g.mu.Unlock()
		failure(w, 503, "request capacity reached")
		return
	}
	// Recheck the payment before a new charge. The lock deliberately serializes
	// this pilot service, including its read-only proof, to avoid stale credit.
	proof, proofErr := g.chain.Verify(r.Context(), inv, inv.TxID)
	if proofErr != nil || proof.Hash != inv.BlockHash || proof.Height != inv.BlockHeight {
		g.mu.Unlock()
		failure(w, 409, "credited payment no longer verified; contact merchant")
		return
	}
	inv.Remaining--
	g.data.Invoices[invoice] = savedInvoice{inv, owner}
	g.data.Calls[key] = Call{Hash: hash, Invoice: invoice, Status: "pending"}
	if g.save() != nil {
		g.mu.Unlock()
		failure(w, 503, "ledger unavailable")
		return
	}
	g.mu.Unlock()
	// The durable reservation precedes the only upstream POST. A timeout/crash
	// leaves it reserved for merchant reconciliation, never an automatic replay.
	req, e := http.NewRequestWithContext(r.Context(), "POST", g.upstream+"/chat/completions", bytes.NewReader(encoded))
	if e == nil {
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+g.key)
	}
	var raw []byte
	if e == nil {
		var resp *http.Response
		resp, e = g.client.Do(req)
		if e == nil {
			raw, e = io.ReadAll(io.LimitReader(resp.Body, 65_537))
			resp.Body.Close()
			if resp.StatusCode != 200 || len(raw) > 65_536 || !validResponse(raw, b.MaxTokens) {
				e = errors.New("upstream failed or response invalid")
			}
		}
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	c := g.data.Calls[key]
	if e != nil {
		c.Status = "unknown"
		g.data.Calls[key] = c
		g.save()
		failure(w, 502, "inference outcome unknown; quota reserved, contact merchant with request id")
		return
	}
	c.Status = "complete"
	c.Response = raw
	g.data.Calls[key] = c
	if g.save() != nil {
		failure(w, 503, "result persistence failed; query the same request id")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(raw)
}

// Reconcile is an offline operator operation. Stop the gateway and acquire its
// ledger lock before using it. Evidence is an operator reference, not a claim
// that this service independently knows what happened at an upstream provider.
func (g *Gateway) Reconcile(owner, id, evidence string, response json.RawMessage, refund bool) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(evidence) < 16 || len(evidence) > 500 || !txPattern.MatchString(owner) || !idPattern.MatchString(id) {
		return errors.New("account hash, request id and 16+ character evidence reference required")
	}
	key := owner + ":" + id
	c, ok := g.data.Calls[key]
	if !ok || (c.Status != "unknown" && c.Status != "pending") {
		return errors.New("request is not awaiting reconciliation")
	}
	saved := g.data.Invoices[c.Invoice]
	if refund {
		if saved.Invoice.Remaining >= saved.Invoice.Count {
			return errors.New("refund would exceed purchased quota")
		}
		saved.Invoice.Remaining++
		g.data.Invoices[c.Invoice] = saved
		c.Status = "refunded"
	} else {
		if !validResponse(response, saved.Invoice.Model.MaxTokens) {
			return errors.New("invalid confirmed completion response")
		}
		c.Status = "complete"
		c.Response = response
	}
	c.Reconciliation = evidence
	g.data.Calls[key] = c
	return g.save()
}
func validResponse(raw []byte, max int) bool {
	var r struct {
		ID      string `json:"id"`
		Object  string `json:"object"`
		Choices []struct {
			Message struct {
				Content *string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			Completion int `json:"completion_tokens"`
			Prompt     int `json:"prompt_tokens"`
		} `json:"usage"`
	}
	return json.Unmarshal(raw, &r) == nil && r.ID != "" && r.Object == "chat.completion" && len(r.Choices) == 1 && r.Choices[0].Message.Content != nil && r.Usage.Completion >= 0 && r.Usage.Completion <= max && r.Usage.Prompt >= 0
}

// BlockbookProof checks the raw transaction amount and the canonical block.
// This trusts the configured indexer; it is deliberately not called SPV.
type BlockbookProof struct {
	Client *http.Client
	Base   string
}

func NewBlockbook(base string) (*BlockbookProof, error) {
	if !secureURL(base) {
		return nil, errors.New("HTTPS Blockbook required")
	}
	return &BlockbookProof{&http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, strings.TrimRight(base, "/") + "/"}, nil
}
func (b *BlockbookProof) get(ctx context.Context, path string, out any) error {
	r, e := http.NewRequestWithContext(ctx, "GET", b.Base+path, nil)
	if e != nil {
		return e
	}
	v, e := b.Client.Do(r)
	if e != nil {
		return e
	}
	defer v.Body.Close()
	if v.StatusCode != 200 {
		return errors.New("index HTTP error")
	}
	raw, e := io.ReadAll(io.LimitReader(v.Body, 2_000_001))
	if e != nil || len(raw) > 2_000_000 {
		return errors.New("index response too large")
	}
	return json.Unmarshal(raw, out)
}
func (b *BlockbookProof) Height(ctx context.Context) (int64, error) {
	var s struct {
		Blockbook struct {
			Coin     string `json:"coin"`
			InSync   bool   `json:"inSync"`
			Decimals int    `json:"decimals"`
		} `json:"blockbook"`
		Backend struct {
			Chain  string `json:"chain"`
			Blocks int64  `json:"blocks"`
		} `json:"backend"`
	}
	e := b.get(ctx, "", &s)
	if e != nil {
		return 0, e
	}
	if s.Blockbook.Coin != "Pearl" || !s.Blockbook.InSync || s.Blockbook.Decimals != 8 || s.Backend.Chain != "mainnet" || s.Backend.Blocks <= 0 {
		return 0, errors.New("Pearl mainnet index not ready")
	}
	return s.Backend.Blocks, nil
}
func (b *BlockbookProof) Verify(ctx context.Context, inv Invoice, id string) (Proof, error) {
	var proof Proof
	if !txPattern.MatchString(id) {
		return proof, errors.New("invalid txid")
	}
	tip, e := b.Height(ctx)
	if e != nil {
		return proof, e
	}
	var tx struct {
		ID            string `json:"txid"`
		Hex           string `json:"hex"`
		Hash          string `json:"blockHash"`
		Height        int64  `json:"blockHeight"`
		Time          int64  `json:"blockTime"`
		Confirmations int64  `json:"confirmations"`
	}
	if e = b.get(ctx, "tx/"+id, &tx); e != nil {
		return proof, e
	}
	if tx.ID != id || !txPattern.MatchString(tx.Hash) || tx.Height <= inv.Height || tx.Height > tip || tx.Confirmations < 6 || tip-tx.Height+1 < 6 || tx.Time < inv.Created-60 || tx.Time > inv.Expires {
		return proof, errors.New("payment outside invoice window or needs 6 confirmations")
	}
	raw, e := hex.DecodeString(tx.Hex)
	if e != nil || len(raw) > 100_000 {
		return proof, errors.New("invalid transaction bytes")
	}
	reader := bytes.NewReader(raw)
	var wireTx wire.MsgTx
	if e = wireTx.Deserialize(reader); e != nil || reader.Len() != 0 || wireTx.TxHash().String() != id {
		return proof, errors.New("transaction hash mismatch")
	}
	if len(wireTx.TxIn) == 1 && wireTx.TxIn[0].PreviousOutPoint.Index == 0xffffffff && wireTx.TxIn[0].PreviousOutPoint.Hash == ([32]byte{}) {
		return proof, errors.New("coinbase rewards are not invoice payments")
	}
	a, e := btcutil.DecodeAddress(inv.Address, &chaincfg.MainNetParams)
	if e != nil {
		return proof, e
	}
	script, e := txscript.PayToAddrScript(a)
	if e != nil {
		return proof, e
	}
	var total int64
	for _, out := range wireTx.TxOut {
		if out.Value < 0 || out.Value > walletcore.MaxMoney {
			return proof, errors.New("invalid output value")
		}
		if bytes.Equal(out.PkScript, script) {
			if total > walletcore.MaxMoney-out.Value {
				return proof, errors.New("amount overflow")
			}
			total += out.Value
		}
	}
	if total != inv.Amount {
		return proof, errors.New("invoice recipient amount mismatch")
	}
	var canonical struct {
		Hash string `json:"blockHash"`
	}
	if e = b.get(ctx, fmt.Sprintf("block-index/%d", tx.Height), &canonical); e != nil {
		return proof, e
	}
	if canonical.Hash != tx.Hash {
		return proof, errors.New("payment block is no longer canonical")
	}
	return Proof{tx.Hash, tx.Height}, nil
}
