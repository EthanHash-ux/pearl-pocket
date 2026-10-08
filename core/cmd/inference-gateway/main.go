// inference-gateway is an optional merchant service, separate from the APK.
package main

import (
	"encoding/json"
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"pearlwallet/inference"
	"syscall"
	"time"
)

func main() {
	account := flag.String("reconcile-account", "", "hashed customer account from ledger")
	requestID := flag.String("reconcile-request", "", "unknown/pending request id")
	evidence := flag.String("evidence", "", "operator reference to verified upstream outcome")
	refund := flag.Bool("refund-known-failure", false, "restore one quota unit after verifying the provider did not fulfill the request")
	resultFile := flag.String("confirmed-response", "", "local provider completion JSON for a fulfilled request")
	flag.Parse()
	configPath := os.Getenv("PRL_GATEWAY_CONFIG")
	b, e := os.ReadFile(configPath)
	if e != nil {
		log.Fatal("set PRL_GATEWAY_CONFIG to the merchant configuration file")
	}
	var cfg inference.Config
	if json.Unmarshal(b, &cfg) != nil {
		log.Fatal("invalid merchant config")
	}
	ledger := os.Getenv("PRL_GATEWAY_LEDGER")
	if ledger == "" {
		ledger = "merchant-data/ledger.json"
	}
	if e = os.MkdirAll(filepath.Dir(ledger), 0700); e != nil {
		log.Fatal(e)
	}
	lock, e := os.OpenFile(ledger+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		log.Fatal(e)
	}
	defer lock.Close()
	if syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) != nil {
		log.Fatal("another gateway is using this ledger")
	}
	base := os.Getenv("PRL_GATEWAY_BLOCKBOOK")
	if base == "" {
		base = "https://blockbook.pearlresearch.ai/api/v2/"
	}
	chain, e := inference.NewBlockbook(base)
	if e != nil {
		log.Fatal(e)
	}
	g, e := inference.Open(ledger, cfg, chain, os.Getenv("PRL_GATEWAY_UPSTREAM"), os.Getenv("PRL_GATEWAY_API_KEY"), os.Getenv("PRL_GATEWAY_SIGNUP_CODE"))
	if e != nil {
		log.Fatal(e)
	}
	if *requestID != "" {
		var response json.RawMessage
		if (*refund) == (*resultFile != "") {
			log.Fatal("select exactly one of refund-known-failure or confirmed-response")
		}
		if *resultFile != "" {
			response, e = os.ReadFile(*resultFile)
			if e != nil || len(response) > 65536 {
				log.Fatal("cannot read confirmed provider response")
			}
		}
		if e = g.Reconcile(*account, *requestID, *evidence, response, *refund); e != nil {
			log.Fatal(e)
		}
		log.Println("request reconciliation saved")
		return
	}
	// Only loopback HTTP. Publish through a TLS reverse proxy with request/rate
	// limits. No cleartext public listener and no private-key configuration.
	s := &http.Server{Addr: "127.0.0.1:8787", Handler: g, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 100 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	log.Println("PRL inference merchant listening on loopback port 8787")
	log.Fatal(s.ListenAndServe())
}
