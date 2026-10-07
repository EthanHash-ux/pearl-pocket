package walletcore

import (
	"strings"
	"testing"
)

func TestExternalEthereumAddressEIP55(t *testing.T) {
	for _, want := range []string{
		"0x52908400098527886E0F7030069857D2E4169EE7",
		"0x8617E340B3D01FA5F11F306F4090FD50E238070D",
		"0xde709f2102306220921060314715629080e2fb77",
		"0x27b1fdb04752bbc536007a920d24acb045561c26",
		"0x5AEDA56215b167893e80B4fE645BA6d5Bab767DE",
		"0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
		"0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
		"0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
	} {
		t.Run(want, func(t *testing.T) {
			got, err := EthereumAddress(want)
			if err != nil || got != want {
				t.Fatalf("checksum: %q %v", got, err)
			}
			got, err = EthereumAddress(strings.ToLower(want))
			if err != nil || got != want {
				t.Fatalf("lowercase: %q %v", got, err)
			}
			out := Execute(`{"action":"ethaddress","raw":"` + want + `"}`)
			if !strings.Contains(out, want) || strings.Contains(out, "entropy") {
				t.Fatal(out)
			}
		})
	}
}

func TestExternalEthereumAddressRejectsWrongChecksumAndZero(t *testing.T) {
	for _, bad := range []string{"0x0000000000000000000000000000000000000000", "0x52908400098527886e0F7030069857D2E4169EE7", "0x5aEDA56215b167893e80B4fE645BA6d5Bab767DE", "0x1234", "0x" + strings.Repeat("z", 40), "0X" + strings.Repeat("a", 40)} {
		if _, err := EthereumAddress(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}
