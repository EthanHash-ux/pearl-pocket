package walletcore

import (
	"bytes"
	"github.com/pearl-research-labs/pearl/node/btcec/schnorr"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/txscript"
	"testing"
)

func TestInvoiceWatchOnlyAddressMatchesRecoverablePrivateDerivation(t *testing.T) {
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
	pub, _ := k.Neuter()
	for _, i := range []uint32{1, 2, 1000, 1<<31 - 1} {
		branch, _ := k.DeriveNonStandard(0)
		child, e := branch.DeriveNonStandard(i)
		branch.Zero()
		if e != nil {
			t.Fatal(e)
		}
		key, _ := child.ECPrivKey()
		child.Zero()
		a, _ := btcutil.NewAddressTaproot(schnorr.SerializePubKey(txscript.ComputeTaprootKeyNoScript(key.PubKey())), &chaincfg.MainNetParams)
		key.Zero()
		got, e := InvoiceAddress(pub.String(), i)
		if e != nil || got != a.EncodeAddress() {
			t.Fatal(got, e)
		}
	}
	for _, i := range []uint32{0, 1 << 31} {
		if _, e = InvoiceAddress(pub.String(), i); e == nil {
			t.Fatal("accepted unsafe index")
		}
	}
	if _, e = InvoiceAddress(k.String(), 1); e == nil {
		t.Fatal("accepted private extended key")
	}
	root, _ := hdkeychain.NewMaster(bytes.Repeat([]byte{0x42}, 32), &chaincfg.MainNetParams)
	defer root.Zero()
	rootPub, _ := root.Neuter()
	if _, e = InvoiceAddress(rootPub.String(), 1); e == nil {
		t.Fatal("accepted wrong-depth xpub")
	}
}
func TestOfflineMerchantCanRecoverInvoiceFundsWithoutChangingAndroidPath(t *testing.T) {
	entropy := make([]byte, 16)
	pub, e := MerchantAccountPublic(entropy)
	if e != nil {
		t.Fatal(e)
	}
	addr, e := InvoiceAddress(pub, 1)
	if e != nil {
		t.Fatal(e)
	}
	to := identityFor(t, make([]byte, 32)).Address
	p := Payment{From: addr, To: to, Amount: 100000, Rate: 1000, UTXOs: []UTXO{funding(t, addr, 200000, 1, false, 10)}}
	q, e := Plan(p)
	if e != nil {
		t.Fatal(e)
	}
	signed, e := MerchantSign(entropy, 1, q)
	if e != nil || signed.TxID == "" {
		t.Fatal(e)
	}
	if _, e = Sign(entropy, q); e == nil {
		t.Fatal("Android fixed path unexpectedly signed merchant index")
	}
	if _, e = MerchantSign(entropy, 2, q); e == nil {
		t.Fatal("wrong index signed invoice")
	}
}
