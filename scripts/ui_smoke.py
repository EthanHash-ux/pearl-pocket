#!/usr/bin/env python3
"""Run current UI/market checks on a disposable emulator.
Clears the installed test wallet; never run against a funded wallet.
Build :app:assembleDebug :app:assembleDebugAndroidTest first.
"""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--reset-test-wallet', action='store_true', required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    project = Path(__file__).resolve().parent.parent
    (project/'artifacts').mkdir(parents=True, exist_ok=True)

    def run(*parts, check=True, timeout=45):
        return subprocess.run(adb + list(parts), check=check, capture_output=True, text=True, timeout=timeout)

    assert args.reset_test_wallet
    assert args.serial.startswith('emulator-') and run('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1', 'Requires a disposable emulator'
    app = project/'app/build/outputs/apk/debug/app-debug.apk'
    tests = project/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    assert app.exists() and tests.exists(), 'Build both debug APKs first'
    run('uninstall', 'ai.pearl.wallet.test', check=False)
    run('uninstall', 'ai.pearl.wallet', check=False)
    run('install', str(app)); run('install', str(tests))
    output = run('shell', 'am', 'instrument', '-w', '-e', 'class', 'ai.pearl.wallet.WalletUiTest',
                 'ai.pearl.wallet.test/androidx.test.runner.AndroidJUnitRunner', timeout=360).stdout
    (project/'artifacts/ui-smoke-run.txt').write_text(output)
    assert 'OK (4 tests)' in output and 'FAILURES' not in output, 'UI checks failed; inspect ui-smoke-run.txt'
    report = {'result': 'PASS', 'version': '0.4.0', 'device': args.serial,
              'checked': ['24-word creation and backup verification', '24-word bulk restore preserves address',
                          '12-word clipboard restore and count selection', 'spelling/checksum block continuation',
                          '15/18/21-word count selection, bulk/clipboard restore and encrypted vault reload',
                          'derived address confirmation', 'new password after device PIN', 'wrong password rejected',
                          'background closes recovery editor', 'receive QR', 'actual PRL-USDT WSS snapshot',
                          'stop publishes no more quotes', 'resume obtains new live snapshot', 'USDT display', '7-day chart',
                          'local contact save and recipient selection', 'amount payment QR exact-grain clipboard',
                          'local QR image decoding through document-picker result and recipient/amount prefilling',
                          'one-shot price alert configuration and removal', 'mining cost and net-profit UI'],
              'mainnet_funds_used': False, 'private_data_recorded': False}
    (project/'artifacts/ui-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
