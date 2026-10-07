package main

/*
#include <stdlib.h>
*/
import "C"
import "pearlwallet/walletcore"

//export PearlExecute
func PearlExecute(input *C.char) *C.char { return C.CString(walletcore.Execute(C.GoString(input))) }
func main()                              {}
