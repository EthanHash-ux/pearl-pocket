#!/usr/bin/env python3
"""Read-only compatibility smoke test. Never signs or broadcasts a transaction."""
import concurrent.futures
import json
import time
import urllib.request

ADDRESS = "prl1paxv6dqey9v0a38eevuu547sllv0h7rst00x5tkpnu4lptl9rna6sdt4ggl"
URLS = {
    "status": "https://blockbook.pearlresearch.ai/api/v2/",
    "price": "https://api.coingecko.com/api/v3/simple/price?ids=pearl-2&vs_currencies=usd,cny&include_24hr_change=true&include_last_updated_at=true",
    "address": f"https://blockbook.pearlresearch.ai/api/v2/address/{ADDRESS}?details=txs&page=1&pageSize=2",
}


def fetch(item):
    name, url = item
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "PearlWalletAndroid/0.1"})
    with urllib.request.urlopen(request, timeout=25) as response:
        return name, json.load(response)


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = dict(pool.map(fetch, URLS.items()))
    status = results["status"]
    assert status["blockbook"]["coin"] == "Pearl"
    assert status["blockbook"]["decimals"] == 8
    assert status["backend"]["chain"] == "mainnet"
    assert status["blockbook"]["inSync"] and not status["blockbook"]["initialSync"]
    price = results["price"]["pearl-2"]
    assert price["usd"] > 0 and price["cny"] > 0
    assert -60 <= time.time() - price["last_updated_at"] < 900
    account = results["address"]
    assert account["address"] == ADDRESS
    assert int(account["balance"]) >= 0
    assert "unconfirmedBalance" in account and "transactions" in account
    print(json.dumps({"chain": "Pearl mainnet", "height": status["blockbook"]["bestHeight"],
                      "price_usd": price["usd"], "price_cny": price["cny"],
                      "price_updated_at": price["last_updated_at"],
                      "public_address_balance_grains": account["balance"],
                      "result": "PASS (read-only; no wallet funds or credentials used)"}, indent=2))


if __name__ == "__main__":
    main()
