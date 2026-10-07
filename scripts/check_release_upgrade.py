#!/usr/bin/env python3
"""Destructive two-release upgrade check, restricted to a disposable Android emulator.
Uses public recovery fixtures; never transfers funds. The existing private signing key stays outside the repo.
Build the current release and Android test APK, then run package_apk.py first.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reset-test-wallet', action='store_true', required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--sdk', default='/opt/pearl-android-sdk')
    parser.add_argument('--previous-apk', type=Path, required=True)
    parser.add_argument('--signing-dir', type=Path, default=Path.home()/'.pearl-wallet-build/signing')
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    adb = [str(Path(args.sdk)/'platform-tools/adb'), '-s', args.serial]

    def run(*parts, check=True, timeout=60):
        return subprocess.run(adb+list(parts), check=check, capture_output=True, text=True, timeout=timeout)

    assert args.reset_test_wallet and args.serial.startswith('emulator-')
    assert run('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1', 'Disposable emulator only'
    assert args.previous_apk.is_file()
    artifacts = project/'artifacts'
    current = artifacts/'pearl-wallet-android.apk'
    tests = project/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    assert current.is_file() and tests.is_file()
    signer = str(Path(args.sdk)/'build-tools/35.0.0/apksigner')
    def certificate(apk):
        checked = subprocess.run([signer, 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True).stdout
        return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', checked).group(1)
    assert certificate(args.previous_apk) == certificate(current), 'APK certificates differ'
    # A release target accepts only instrumentation signed by the same development certificate.
    credentials = args.signing_dir/'credentials.json'
    keystore = args.signing_dir/'pearl-wallet-development.jks'
    assert credentials.is_file() and keystore.is_file()
    password = json.loads(credentials.read_text())['password']
    signed_tests = artifacts/'release-upgrade-tests.apk'
    subprocess.run([signer, 'sign', '--ks', str(keystore),
                    '--ks-key-alias', 'pearl-wallet', '--ks-pass', 'env:PEARL_SIGN_PASSWORD',
                    '--key-pass', 'env:PEARL_SIGN_PASSWORD', '--out', str(signed_tests), str(tests)],
                   env=dict(os.environ, PEARL_SIGN_PASSWORD=password), check=True, capture_output=True)
    run('uninstall', 'ai.pearl.wallet.test', check=False)
    run('uninstall', 'ai.pearl.wallet', check=False)
    run('install', str(args.previous_apk.resolve()))
    run('install', str(signed_tests))

    def version():
        return re.search(r'versionName=([^\s]+)', run('shell', 'dumpsys', 'package', 'ai.pearl.wallet').stdout).group(1)

    before = version()
    setup = run('shell', 'am', 'instrument', '-w', '-e', 'class',
                'ai.pearl.wallet.WalletUiTest#recoverFifteenEighteenAndTwentyOneWordsAndUnlockPersistedVault',
                'ai.pearl.wallet.test/androidx.test.runner.AndroidJUnitRunner', timeout=240).stdout
    (artifacts/'upgrade-old-wallet-setup.txt').write_text(setup)
    assert 'OK (1 test)' in setup and 'FAILURES' not in setup, 'Old release wallet setup failed'
    run('install', '-r', str(current))  # Preserve the wallet and Keystore entry.
    after = version()
    expected = re.search(r"versionName '([^']+)'", (project/'app/build.gradle').read_text()).group(1)
    assert after == expected and before != after
    output = run('shell', 'am', 'instrument', '-w', '-e', 'class', 'ai.pearl.wallet.WalletUpgradeTest',
                 '-e', 'check_upgrade', 'true', 'ai.pearl.wallet.test/androidx.test.runner.AndroidJUnitRunner', timeout=90).stdout
    (artifacts/'upgrade-wallet-check.txt').write_text(output)
    assert 'OK (1 test)' in output and 'FAILURES' not in output, 'Upgraded wallet check failed'
    report = {'result':'PASS','from':before,'to':after,'same_signing_certificate':True,
              'in_place_install_succeeded':True,'previous_install_had_wallet':True,
              'existing_wallet_address_preserved':True,'backup_status_preserved':True,
              'device_and_password_authentication_preserved':True,'encrypted_wallet_decryptable':True,
              'test_device':'Android '+run('shell','getprop','ro.build.version.release').stdout.strip()+' emulator','mainnet_funds_used':False,'private_data_recorded':False}
    (artifacts/'tools-upgrade.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
