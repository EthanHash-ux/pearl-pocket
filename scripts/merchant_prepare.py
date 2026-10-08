#!/usr/bin/env python3
"""Read-only invoice-address funds preparation. No seed, signing or broadcast."""
import argparse
import json
import subprocess
import urllib.request
from decimal import Decimal, ROUND_CEILING

BASE = 'https://blockbook.pearlresearch.ai/api/v2/'


def fetch(path):
    request = urllib.request.Request(BASE + path, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.url != BASE + path:
            raise ValueError('Unexpected redirect from Pearl index')
        data = response.read(2_000_001)
        if len(data) > 2_000_000:
            raise ValueError('Index response too large')
        return json.loads(data)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--core-cli', required=True)
    p.add_argument('--xpub', required=True)
    p.add_argument('--index', type=int, required=True)
    p.add_argument('--to', required=True, help='your Pearl mainnet settlement address')
    p.add_argument('--output', required=True, help='new local quote file; never overwritten')
    args = p.parse_args()

    def core(value):
        result = subprocess.run([args.core_cli], input=json.dumps(value) + '\n',
                                capture_output=True, text=True, check=True)
        payload = json.loads(result.stdout)
        if 'error' in payload:
            raise ValueError(payload['error'])
        return payload['result']

    source = core({'action': 'invoiceaddress', 'xpub': args.xpub,
                   'index': args.index})['address']
    state = fetch('')
    if (state['blockbook']['coin'] != 'Pearl' or not state['blockbook']['inSync']
            or state['backend']['chain'] != 'mainnet'):
        raise ValueError('Pearl index is not ready')
    rows = fetch('utxo/' + source + '?confirmed=true')
    if len(rows) > 100:
        raise ValueError('More than 100 inputs; prepare a smaller manual batch')
    utxos = []
    for row in rows:
        txid = row['txid']
        if len(txid) != 64 or any(c not in '0123456789abcdef' for c in txid):
            raise ValueError('Invalid indexed transaction ID')
        tx = fetch('tx/' + txid)
        if tx['txid'] != txid:
            raise ValueError('Indexed transaction ID mismatch')
        utxos.append({'txid': txid, 'vout': row['vout'], 'raw': tx['hex'],
                      'confirmations': min(row['confirmations'], tx['confirmations'])})
    rate = max(1000, int((Decimal(fetch('estimatefee/6')['result']) * Decimal(10**8))
                         .to_integral_value(rounding=ROUND_CEILING)))
    quote = core({'action': 'planmax', 'payment': {'from': source, 'to': args.to,
                  'amount': '333', 'rate': rate, 'utxos': utxos}})
    with open(args.output, 'x', encoding='utf-8') as out:
        json.dump(quote, out, ensure_ascii=False, indent=2)
        out.write('\n')
    print(json.dumps({'invoice_index': args.index, 'from': source, 'to': args.to,
                      'amount_grain': quote['payment']['amount'], 'fee_grain': quote['fee'],
                      'expires': quote['expires'], 'quote_file': args.output,
                      'broadcast': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
