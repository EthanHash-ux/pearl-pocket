package walletcore

import (
	"encoding/hex"
	"encoding/json"
	"testing"
	"time"
)

func testTrade(t *testing.T, action string) TradePlan {
	t.Helper()
	id, e := EthereumIdentity(make([]byte, 16))
	if e != nil {
		t.Fatal(e)
	}
	now := time.Now().Unix()
	p := TradePlan{Action: action, From: id["address"], Nonce: uint64(time.Now().UnixMilli()), IssuedAt: now, Expires: now + 240}
	switch action {
	case "place_order":
		p.Side = "sell"
		p.Quantity = "560000000"
		p.Price = "2160000"
	case "cancel_order":
		p.OrderID = "3038666"
	case "withdraw":
		p.Asset = "USDC-ARB"
		p.Quantity = "14256000"
		p.Dest = p.From
	}
	return p
}
func TestTradeRoundTrip(t *testing.T) {
	for _, action := range []string{"place_order", "cancel_order", "withdraw"} {
		t.Run(action, func(t *testing.T) {
			p := testTrade(t, action)
			a, e := TradeSign(make([]byte, 16), p)
			if e != nil {
				t.Fatal(e)
			}
			if _, e = TradeVerify(p, a); e != nil {
				t.Fatal(e)
			}
			p.Expires = p.IssuedAt
			if _, e = TradeVerify(p, a); e != nil {
				t.Fatal("Saved signatures must remain verifiable", e)
			}
			if _, e = TradeSign(make([]byte, 16), p); e == nil {
				t.Fatal("Expired request signed")
			}
		})
	}
	p := testTrade(t, "withdraw")
	p.Asset = "PRL"
	k, address, e := keyFor(make([]byte, 16))
	if e != nil {
		t.Fatal(e)
	}
	k.Zero()
	p.Dest = address
	if _, e = TradeSign(make([]byte, 16), p); e != nil {
		t.Fatal(e)
	}
	k, other, e := keyFor(make([]byte, 20))
	if e != nil {
		t.Fatal(e)
	}
	k.Zero()
	p.Dest = other
	if _, e = TradeSign(make([]byte, 16), p); e == nil {
		t.Fatal("Foreign Pearl recipient signed")
	}
}
func TestTradeRejectsUnreviewedTerms(t *testing.T) {
	cases := map[string]func(*TradePlan){"source": func(p *TradePlan) { p.From = EthereumUSDC }, "action": func(p *TradePlan) { p.Action = "chat" }, "quantity": func(p *TradePlan) { p.Quantity = "99999999" }, "lot": func(p *TradePlan) { p.Quantity = "100000001" }, "tick": func(p *TradePlan) { p.Price = "2160001" }, "side": func(p *TradePlan) { p.Side = "short" }, "price": func(p *TradePlan) { p.Price = "0" }, "extra": func(p *TradePlan) { p.Dest = p.From }, "old": func(p *TradePlan) { p.IssuedAt -= 400; p.Expires = p.IssuedAt + 240 }, "future": func(p *TradePlan) { p.IssuedAt += 100; p.Expires = p.IssuedAt + 240 }, "nonce": func(p *TradePlan) { p.Nonce = 9007199254740992 }}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			p := testTrade(t, "place_order")
			change(&p)
			if _, e := TradeSign(make([]byte, 16), p); e == nil {
				t.Fatal("Unsafe terms signed")
			}
		})
	}
	p := testTrade(t, "withdraw")
	p.Dest = EthereumUSDC
	if _, e := TradeSign(make([]byte, 16), p); e == nil {
		t.Fatal("Foreign USDC recipient signed")
	}
	p = testTrade(t, "withdraw")
	p.Asset = "USDC"
	if _, e := TradeSign(make([]byte, 16), p); e == nil {
		t.Fatal("Wrong chain asset signed")
	}
}
func TestTradeAuthMutation(t *testing.T) {
	p := testTrade(t, "place_order")
	auth, e := TradeSign(make([]byte, 16), p)
	if e != nil {
		t.Fatal(e)
	}
	for _, change := range []func(*TradeAuth){func(a *TradeAuth) { a.Payload += " " }, func(a *TradeAuth) { a.Nonce++ }, func(a *TradeAuth) { a.IssuedAt++ }, func(a *TradeAuth) { a.Address = EthereumUSDC }, func(a *TradeAuth) { a.Signature = a.Signature[:130] + "01" }} {
		a := auth
		change(&a)
		if _, e = TradeVerify(p, a); e == nil {
			t.Fatal("Changed auth accepted")
		}
	}
	b, _ := hex.DecodeString(auth.Signature[2:])
	b[0] ^= 1
	a := auth
	a.Signature = "0x" + hex.EncodeToString(b)
	if _, e = TradeVerify(p, a); e == nil {
		t.Fatal("Changed signature accepted")
	}
	p.Price = "2160100"
	if _, e = TradeVerify(p, auth); e == nil {
		t.Fatal("Price substituted after signing")
	}
}
func TestTradeExecuteNoSecretsInSignedResult(t *testing.T) {
	p := testTrade(t, "place_order")
	b, _ := json.Marshal(Request{Action: "tradesign", Entropy: "AAAAAAAAAAAAAAAAAAAAAA==", Trade: p})
	var r struct {
		Result map[string]any `json:"result"`
		Error  string         `json:"error"`
	}
	if e := json.Unmarshal([]byte(Execute(string(b))), &r); e != nil || r.Error != "" {
		t.Fatal(e, r.Error)
	}
	if len(r.Result) != 5 || r.Result["signature"] == nil || r.Result["entropy"] != nil {
		t.Fatal(r.Result)
	}
}
func TestTradeEthersReferenceDigest(t *testing.T) {
	p := testTrade(t, "place_order")
	p.Nonce = 1791410637000
	p.IssuedAt = 1791410637
	p.Expires = p.IssuedAt + 240
	r, e := TradeIntent(p)
	if e != nil {
		t.Fatal(e)
	}
	if r["digest"] != "0x1bf9b36f3b9f9abdd06607a9ab630eee48da65a207bafeed21423d93ae70bf44" {
		t.Fatal("Ethers v6 reference digest mismatch", r)
	}
}
