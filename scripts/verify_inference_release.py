#!/usr/bin/env python3
"""Verify and prepare the 0.12.0 release from committed source and test evidence."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parent.parent
ARTIFACTS = PROJECT / 'artifacts'
SDK = Path('/opt/pearl-android-sdk')
VERSION = '0.12.0'


def run(*args):
    return subprocess.check_output(args, cwd=PROJECT, text=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert not run('git', 'status', '--porcelain').strip(), 'Commit source first'
    commit = run('git', 'rev-parse', 'HEAD').strip()
    apk = ARTIFACTS / f'pearl-wallet-android-{VERSION}.apk'
    shutil.copyfile(ARTIFACTS / 'pearl-wallet-android.apk', apk)
    tools = SDK / 'build-tools/35.0.0'
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk))
    assert "versionCode='13' versionName='0.12.0'" in badging
    assert 'application-debuggable' not in badging
    assert "native-code: 'arm64-v8a' 'x86_64'" in badging
    run(str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk))
    signature = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    (ARTIFACTS / 'apk-signature.txt').write_text(signature)
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature
    previous = run(str(tools / 'apksigner'), 'verify', '--print-certs',
                   str(ARTIFACTS / 'pearl-wallet-android-0.11.0.apk'))
    cert = r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)'
    assert re.search(cert, signature).group(1) == re.search(cert, previous).group(1)
    excluded = {'META-INF/version-control-info.textproto', 'META-INF/PEARL-WA.SF',
                'META-INF/PEARL-WA.RSA', 'META-INF/MANIFEST.MF'}
    with zipfile.ZipFile(apk) as delivered, zipfile.ZipFile(ARTIFACTS / 'inference-tested.apk') as tested:
        assert delivered.testzip() is None
        info = delivered.read('META-INF/version-control-info.textproto').decode()
        assert re.findall(r'revision:\s*"([a-f0-9]{40})"', info) == [commit]
        files = set(delivered.namelist()) - excluded
        assert files == set(tested.namelist()) - excluded
        assert all(delivered.read(f) == tested.read(f) for f in files), 'Production differs from tested APK'
        native = {}
        for abi in ('arm64-v8a', 'x86_64'):
            binary = delivered.read(f'lib/{abi}/libpearlcore.so')
            native[abi] = hashlib.sha256(binary).hexdigest()
            path = ARTIFACTS / f'inference-{abi}.so'
            path.write_bytes(binary)
            elf = run(str(SDK / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'), '-lW', str(path))
            loads = [line for line in elf.splitlines() if line.strip().startswith('LOAD')]
            assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads)
    source = ARTIFACTS / 'pearl-wallet-android-source.zip'
    with zipfile.ZipFile(source, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for f in run('git', 'ls-files').splitlines():
            archive.writestr('pearl-wallet-android/' + f,
                             subprocess.check_output(['git', 'show', 'HEAD:' + f], cwd=PROJECT))
    java = 0
    for path in (PROJECT / 'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        assert suite.get('failures') == '0' and suite.get('errors') == '0'
        java += int(suite.get('tests'))
    assert java == 93
    assert not ET.parse(PROJECT / 'app/build/reports/lint-results-release.xml').getroot().findall('issue')
    go = [json.loads(s) for s in (ARTIFACTS / 'inference-go-tests.jsonl').read_text().splitlines()]
    assert not any(row['Action'] == 'fail' for row in go)
    top = sum(row['Action'] == 'pass' and 'Test' in row and '/' not in row['Test'] for row in go)
    sub = sum(row['Action'] == 'pass' and 'Test' in row and '/' in row['Test'] for row in go)
    assert top == 32
    evidence = {'inference-android.txt': 4, 'inference-android-regression.txt': 9,
                'inference-android-upgrade.txt': 1}
    for name, count in evidence.items():
        output = (ARTIFACTS / name).read_text()
        assert f'OK ({count} test' in output and 'FAILURES' not in output
    simnet = json.loads((ARTIFACTS / 'inference-simnet-transfer.json').read_text())
    assert simnet['result'] == 'PASS' and simnet['maximum_transfer']['result'] == 'PASS'
    (ARTIFACTS / 'simnet-transfer.json').write_text(json.dumps(simnet, indent=2) + '\n')
    ui = json.loads((ARTIFACTS / 'inference-ui-proof.json').read_text())
    assert ui['result'] == 'PASS' and not ui['mainnet_funds_used']
    assert ui['mock_prl_broadcasts'] == 1 and ui['mock_calls'] == 2
    upgrade = json.loads((ARTIFACTS / 'tools-upgrade.json').read_text())
    assert upgrade['result'] == 'PASS' and upgrade['from'] == '0.11.0' and upgrade['to'] == VERSION
    verification = {'result': 'PASS', 'version': VERSION, 'version_code': 13,
        'verified_at': datetime.now(timezone.utc).isoformat(), 'source_git_commit': commit,
        'apk_sha256': sha(apk), 'source_sha256': sha(source), 'apk_debuggable': False,
        'supported_abis': list(native), 'native_core_sha256': native,
        'elf_and_zip_page_alignment': 16384, 'java_unit_tests': java,
        'go_race_tests': top, 'go_subtests': sub, 'lint_issues': 0,
        'android_instrumentation_tests': sum(evidence.values()), 'android_evidence': evidence,
        'upgrade': upgrade, 'simnet_transfer': simnet, 'production_files_compared': len(files),
        'excluded_metadata_and_v1_signature': sorted(excluded),
        'usdt_purchase': {'pair': 'PRL/USDT', 'venue': 'BigONE',
                          'execution': 'external venue page; no private exchange trading API'},
        'inference': {'protocol': 'pearl-inference-1', 'network': 'Pearl mainnet',
           'pricing': 'prepaid model-specific request count with fixed input/output limits',
           'confirmations': 6, 'server_keys': 'watch-only account xpub and upstream API key; no wallet seed',
           'customer_credentials': 'separate per-wallet Android Keystore AES-GCM',
           'public_merchant_deployed': False, 'funded_mainnet_payment_tested': False,
           'paid_provider_called': False, 'android_ui': ui},
        'mainnet_funds_used': False,
        'limits': ['Development prerelease; no independent security audit or physical-phone testing',
          'USDT buying opens the BigONE venue page; requires the customer venue account and withdrawal',
          'No public PRL inference merchant deployed; requires operator domain, account xpub and valid upstream key',
          'Official Pearl inference uses USD credits/Stripe; this is a separate merchant service',
          'Inference writes, payments and completions tested with substitutes, not mainnet funds or paid models',
          'Single-process pilot ledger: bounded accounts/invoices/requests, manual unknown-request reconciliation',
          'PRL payments are merchant prepayments; no automatic PRL refunds or on-chain escrow',
          'Configured Blockbook is trusted, not SPV; 6-block confirmation has residual reorg risk',
          'No self-service customer key revocation, provider switching, merchant discovery or account migration']}
    (ARTIFACTS / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2) + '\n')
    report = f'''# Pearl Pocket 0.12.0 验证

源码提交 `{commit}`；交付 APK 的 {len(files)} 个生产文件与实际模拟器测试包逐字节一致，仅排除 Git 元数据与 v1 签名。原有签名证书、release 非调试、两种 ABI、16 KB ELF / ZIP 对齐通过。

- Java：{java} 项单元测试通过；Go race：{top} 项顶层测试、{sub} 项子测试通过；Android lint：0 问题。
- Android：{sum(evidence.values())} 项指定检查通过。新 PRL 推理 JNI / 加密凭据 / 不变账单 / 完整支付和调用 UI 共 4 项；原钱包与现货回归 9 项；0.11.0 → 0.12.0 升级保留地址、设备认证、密码解密和备份状态 1 项。不是整个 instrumentation 测试集。
- 推理 UI 使用真实系统 PIN 和钱包密码；PRL 广播、商家账单和推理服务全部模拟。验证同一账单本机只付款一次、到账后领取额度、调用回复、不明结果阻止另一个调用。
- 商家 HTTPS 测试覆盖独立地址、账户归属、6 确认、规范区块、原始交易哈希与金额、付款窗口、付款防重放、并发调用预留、重启后缓存结果、输入 / 输出限制和不明调用核对，不会重复退额度。
- 商家离线签名验证 index 1 的资金可用匹配助记词签署，并通过官方脚本验证；Android 原有 index 0 不能替代该 index。
- 固定 Pearl 官方隔离 simnet 上再次完成普通与全部余额转账、节点接收和出块确认。Go vet 与 Python 语法检查通过。
- UI 测试修正了对关闭对话框和页面返回状态的假设；所有最终指定检查通过。测试 APK 使用与 release 相同证书。

没有主网实币购买 / PRL 服务付款、没有实际收费模型调用、没有配置或部署公共商家。USDT 购买为外部 BigONE 页面入口；PRL 推理是单进程、有限容量的独立商家预付服务，不是官方美元平台的 PRL 充值 API。真实上线需要商家配置与运营核对。详细部署和边界见源码 docs/AI_INFERENCE.md。

APK SHA-256：`{sha(apk)}`
'''
    (ARTIFACTS / 'TEST_REPORT.md').write_text(report)
    assets = [apk.name, source.name, 'verification.json', 'TEST_REPORT.md', 'apk-signature.txt',
              'simnet-transfer.json', 'inference-go-tests.jsonl', 'inference-ui-proof.json', *evidence]
    (ARTIFACTS / 'SHA256SUMS').write_text(''.join(f'{sha(ARTIFACTS / f)}  {f}\n' for f in assets))
    print(json.dumps({'result': 'PASS', 'version': VERSION, 'commit': commit,
                      'java': java, 'go': top, 'android': sum(evidence.values()),
                      'apk_sha256': sha(apk)}, indent=2))


if __name__ == '__main__':
    main()
