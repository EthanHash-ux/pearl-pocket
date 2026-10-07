module pearlwallet

go 1.26.6

require (
	github.com/pearl-research-labs/pearl v0.0.0
	github.com/tyler-smith/go-bip39 v1.1.0
)

require (
	github.com/btcsuite/btclog v1.0.0 // indirect
	github.com/decred/dcrd/crypto/blake256 v1.1.0 // indirect
	github.com/decred/dcrd/dcrec/secp256k1/v4 v4.4.1 // indirect
	golang.org/x/crypto v0.57.0 // indirect
	golang.org/x/sys v0.48.0 // indirect
)

replace github.com/pearl-research-labs/pearl => ../../pearl-upstream
