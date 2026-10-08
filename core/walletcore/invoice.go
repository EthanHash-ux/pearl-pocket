package walletcore

import (
	"errors"
	"github.com/pearl-research-labs/pearl/node/btcec/schnorr"
	"github.com/pearl-research-labs/pearl/node/btcutil"
	"github.com/pearl-research-labs/pearl/node/btcutil/hdkeychain"
	"github.com/pearl-research-labs/pearl/node/chaincfg"
	"github.com/pearl-research-labs/pearl/node/txscript"
	bip39 "github.com/tyler-smith/go-bip39"
)

// MerchantAccountPublic is used only by the offline desktop merchant tool.
// It is deliberately absent from the Android JNI action dispatcher.
func merchantAccount(entropy []byte) (*hdkeychain.ExtendedKey, error) {
	m, e := bip39.NewMnemonic(entropy)
	if e != nil {
		return nil, e
	}
	seed := bip39.NewSeed(m, "")
	defer wipe(seed)
	k, e := hdkeychain.NewMaster(seed, &chaincfg.MainNetParams)
	if e != nil {
		return nil, e
	}
	for _, i := range []uint32{86 + hdkeychain.HardenedKeyStart, 808276 + hdkeychain.HardenedKeyStart, hdkeychain.HardenedKeyStart} {
		child, err := k.DeriveNonStandard(i)
		k.Zero()
		if err != nil {
			return nil, err
		}
		k = child
	}
	return k, nil
}
func MerchantAccountPublic(entropy []byte) (string, error) {
	k, e := merchantAccount(entropy)
	if e != nil {
		return "", e
	}
	defer k.Zero()
	pub, e := k.Neuter()
	if e != nil {
		return "", e
	}
	defer pub.Zero()
	return pub.String(), nil
}

// MerchantSign permits recovery of an invoice address using the merchant's
// offline mnemonic. Signing still verifies all selected UTXOs and the quote.
func MerchantSign(entropy []byte, index uint32, q Quote) (Signed, error) {
	if index < 1 || index >= hdkeychain.HardenedKeyStart {
		return Signed{}, errors.New("invalid invoice index")
	}
	k, e := merchantAccount(entropy)
	if e != nil {
		return Signed{}, e
	}
	defer k.Zero()
	pub, e := k.Neuter()
	if e != nil {
		return Signed{}, e
	}
	defer pub.Zero()
	address, e := InvoiceAddress(pub.String(), index)
	if e != nil {
		return Signed{}, e
	}
	branch, e := k.DeriveNonStandard(0)
	if e != nil {
		return Signed{}, e
	}
	defer branch.Zero()
	child, e := branch.DeriveNonStandard(index)
	if e != nil {
		return Signed{}, e
	}
	defer child.Zero()
	priv, e := child.ECPrivKey()
	if e != nil {
		return Signed{}, e
	}
	defer priv.Zero()
	return signWithKey(priv, address, q)
}

// InvoiceAddress is watch-only: the server must receive an account public key,
// never a mnemonic or private key. Invoice addresses are m/account/0/index.
// This account belongs to the merchant, not to an Android customer's wallet.
func InvoiceAddress(xpub string, index uint32) (string, error) {
	k, err := hdkeychain.NewKeyFromString(xpub)
	if err != nil {
		return "", errors.New("收款扩展公钥无效")
	}
	defer k.Zero()
	if k.IsPrivate() || !k.IsForNet(&chaincfg.MainNetParams) || k.Depth() != 3 || k.ChildIndex() != hdkeychain.HardenedKeyStart || index == 0 || index >= hdkeychain.HardenedKeyStart {
		return "", errors.New("需要 Pearl 主网 account 0 的扩展公钥和独立账单序号")
	}
	branch, err := k.DeriveNonStandard(0)
	if err != nil {
		return "", err
	}
	defer branch.Zero()
	child, err := branch.DeriveNonStandard(index)
	if err != nil {
		return "", err
	}
	defer child.Zero()
	pub, err := child.ECPubKey()
	if err != nil {
		return "", err
	}
	a, err := btcutil.NewAddressTaproot(schnorr.SerializePubKey(txscript.ComputeTaprootKeyNoScript(pub)), &chaincfg.MainNetParams)
	if err != nil {
		return "", err
	}
	return a.EncodeAddress(), nil
}
