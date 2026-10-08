// Offline public-key export and invoice-funds recovery. Never run on a server.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	bip39 "github.com/tyler-smith/go-bip39"
	"golang.org/x/sys/unix"
	"os"
	"pearlwallet/walletcore"
	"strings"
)

func run() error {
	quotePath := flag.String("sign-quote", "", "optional local transfer quote; produces signed bytes without broadcasting")
	index := flag.Uint("index", 1, "invoice derivation index")
	flag.Parse()
	if *index < 1 || *index >= 1<<31 {
		return fmt.Errorf("invalid index")
	}
	var q walletcore.Quote
	if *quotePath != "" {
		b, e := os.ReadFile(*quotePath)
		if e != nil {
			return e
		}
		if len(b) > 32000000 || json.Unmarshal(b, &q) != nil {
			return fmt.Errorf("invalid transfer quote")
		}
		fmt.Fprintf(os.Stderr, "SIGN REVIEW\nInvoice index: %d\nFrom: %s\nTo: %s\nAmount (grain): %d\nFee (grain): %d\nType SIGN to authorize producing signed bytes: ", *index, q.Payment.From, q.Payment.To, q.Payment.Amount, q.Fee)
		text, e := bufio.NewReader(os.Stdin).ReadString('\n')
		if e != nil || strings.TrimSpace(text) != "SIGN" {
			return fmt.Errorf("signing cancelled")
		}
	}
	fd := int(os.Stdin.Fd())
	old, e := unix.IoctlGetTermios(fd, unix.TCGETS)
	if e != nil {
		return fmt.Errorf("run on an offline Linux terminal; seed input from files/arguments is refused")
	}
	masked := *old
	masked.Lflag &^= unix.ECHO
	if e = unix.IoctlSetTermios(fd, unix.TCSETS, &masked); e != nil {
		return e
	}
	defer unix.IoctlSetTermios(fd, unix.TCSETS, old)
	fmt.Fprint(os.Stderr, "Merchant mnemonic (offline terminal, hidden input): ")
	phrase, e := bufio.NewReader(os.Stdin).ReadString('\n')
	fmt.Fprintln(os.Stderr)
	if e != nil {
		return e
	}
	phrase = strings.Join(strings.Fields(phrase), " ")
	entropy, e := bip39.EntropyFromMnemonic(phrase)
	phrase = ""
	if e != nil {
		return fmt.Errorf("invalid English BIP39 phrase")
	}
	defer func() {
		for i := range entropy {
			entropy[i] = 0
		}
	}()
	if *quotePath != "" {
		signed, e := walletcore.MerchantSign(entropy, uint32(*index), q)
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(signed)
	}
	pub, e := walletcore.MerchantAccountPublic(entropy)
	if e != nil {
		return e
	}
	addr, e := walletcore.InvoiceAddress(pub, uint32(*index))
	if e != nil {
		return e
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"account_xpub": pub, "path": fmt.Sprintf("m/86'/808276'/0'/0/%d", *index), "index": *index, "address": addr})
}
func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
