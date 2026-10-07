package main

import (
	"bufio"
	"fmt"
	"os"
	"pearlwallet/walletcore"
)

func main() {
	s := bufio.NewScanner(os.Stdin)
	s.Buffer(make([]byte, 4096), 32000000)
	for s.Scan() {
		fmt.Println(walletcore.Execute(s.Text()))
	}
}
