#!/usr/bin/env python3
"""Read-only PRL-USDT REST and WSS check; requires websocket-client.
Does not use an account, API key, wallet address, signing or transactions.
"""
import decimal
import json
import time
import urllib.request
from pathlib import Path
import websocket


def main():
    request = urllib.request.Request('https://api.big.one/api/v3/asset_pairs/PRL-USDT/ticker',
                                     headers={'User-Agent': 'PearlWalletAndroid/0.3', 'Cache-Control': 'no-cache'})
    with urllib.request.urlopen(request, timeout=15) as response:
        rest = json.load(response)
    assert rest['code'] == 0 and rest['data']['asset_pair_name'] == 'PRL-USDT'
    assert decimal.Decimal(rest['data']['close']) > 0
    socket = websocket.create_connection('wss://api.big.one/ws/v2', timeout=15,
                                          header=['Sec-WebSocket-Protocol: json'])
    observations = []
    deadline = time.monotonic() + 90
    try:
        socket.send(json.dumps({'requestId': 'pearl-market-check',
                               'subscribeMarketsTickerRequest': {'markets': ['PRL-USDT']}}))
        while time.monotonic() < deadline:
            socket.settimeout(min(10, deadline-time.monotonic()))
            try:
                message = json.loads(socket.recv())
            except websocket.WebSocketTimeoutException:
                socket.ping(); continue
            if 'tickerUpdate' in message:
                ticker = message['tickerUpdate']['ticker']; kind = 'tickerUpdate'
            elif 'tickersSnapshot' in message:
                ticker = next(t for t in message['tickersSnapshot']['tickers'] if t['market'] == 'PRL-USDT'); kind = 'tickersSnapshot'
            else:
                continue
            assert ticker['market'] == 'PRL-USDT' and decimal.Decimal(ticker['close']) > 0
            observations.append({'event': kind, 'close_usdt': ticker['close'], 'received_at': int(time.time())})
            if any(o['event'] == 'tickerUpdate' for o in observations):
                break
    finally:
        socket.close()
    assert any(o['event'] == 'tickersSnapshot' for o in observations), 'No WSS snapshot'
    assert any(o['event'] == 'tickerUpdate' for o in observations), 'No pushed ticker within the observation window'
    report = {'result': 'PASS', 'market': 'PRL-USDT', 'source': 'BigONE', 'rest_quote_usdt': rest['data']['close'],
              'observations': observations, 'wallet_data_sent': False, 'credentials_used': False, 'mainnet_funds_used': False}
    output = Path(__file__).resolve().parent.parent/'artifacts/live-market.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
