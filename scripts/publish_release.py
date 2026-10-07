#!/usr/bin/env python3
"""Publish the verified Android build to an explicitly selected GitHub repository."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

PROJECT = Path(__file__).resolve().parent.parent
VERSION = '0.8.0'
TAG = 'v' + VERSION
ASSETS = [f'pearl-wallet-android-{VERSION}.apk', 'pearl-wallet-android-source.zip',
          'SHA256SUMS', 'TEST_REPORT.md', 'verification.json',
          'simnet-transfer.json', 'apk-signature.txt',
          'ui-wallet.png', 'ui-market.png', 'ui-mining.png', 'ui-settings.png', 'ui-wallet-dark.png', 'ui-market-dark.png', 'ui-cross-chain.png', 'ui-fortune.png', 'ui-mining-widget.png']


def run(*args, check=True):
    return subprocess.run(args, cwd=PROJECT, check=check, capture_output=True, text=True)


def publish():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, help='GitHub OWNER/REPOSITORY; repository must already exist')
    parser.add_argument('--dry-run', action='store_true', help='Validate local release files without changing GitHub')
    parser.add_argument('--expected-visibility', choices=('private', 'public', 'internal'),
                        help='Require this repository visibility before uploading any source or assets')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', args.repo):
        raise ValueError('Use an explicit GitHub OWNER/REPOSITORY')
    if run('git', 'branch', '--show-current').stdout.strip() != 'main':
        raise ValueError('Publish from the main branch')
    if run('git', 'status', '--porcelain').stdout.strip():
        raise ValueError('Commit the source changes before publishing')
    commit = run('git', 'rev-parse', 'HEAD').stdout.strip()
    if f"versionName '{VERSION}'" not in (PROJECT/'app/build.gradle').read_text():
        raise ValueError('Android version differs from release version')
    artifacts = PROJECT/'artifacts'
    for name in ASSETS:
        if not (artifacts/name).is_file():
            raise ValueError(f'Missing release asset: {name}')
    sums = {}
    for line in (artifacts/'SHA256SUMS').read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.lstrip('*')
        if Path(name).name != name:
            raise ValueError('Checksums must use plain asset filenames')
        sums[name] = digest
    for name in ASSETS:
        if name == 'SHA256SUMS':
            continue
        digest = hashlib.sha256((artifacts/name).read_bytes()).hexdigest()
        if sums.get(name) != digest:
            raise ValueError(f'Checksum mismatch: {name}')
    verification = json.loads((artifacts/'verification.json').read_text())
    if verification['version'] != VERSION or verification['apk_debuggable']:
        raise ValueError('Release verification does not describe a non-debuggable current build')
    if verification.get('source_git_commit') != commit:
        raise ValueError('Release verification refers to a different source commit')
    if verification['apk_sha256'] != sums[ASSETS[0]] or verification['source_sha256'] != sums[ASSETS[1]]:
        raise ValueError('Verification and release checksums disagree')
    with zipfile.ZipFile(artifacts/ASSETS[0]) as apk:
        vcs = apk.read('META-INF/version-control-info.textproto').decode('utf-8')
        revisions = re.findall(r'revision:\s*"([0-9a-f]{40})"', vcs)
        if revisions != [commit]:
            raise ValueError('APK was built from a different source commit')
    # The attached source archive must contain the exact source stored in Git.
    with zipfile.ZipFile(artifacts/ASSETS[1]) as archive:
        expected = {f'pearl-wallet-android/{name}' for name in run('git', 'ls-files').stdout.splitlines()}
        if set(archive.namelist()) != expected or archive.testzip() is not None:
            raise ValueError('Source ZIP does not match the committed file list')
        for name in expected:
            relative = name.removeprefix('pearl-wallet-android/')
            committed = subprocess.run(['git', 'show', f'HEAD:{relative}'], cwd=PROJECT,
                                       check=True, capture_output=True).stdout
            if archive.read(name) != committed:
                raise ValueError(f'Source ZIP differs from the committed source: {relative}')
    print(json.dumps({'repo': args.repo, 'tag': TAG, 'commit': commit,
                      'prerelease': True, 'assets': ASSETS, 'local_validation': 'PASS'}, indent=2))
    if args.dry_run:
        return
    run('gh', 'auth', 'status', '--hostname', 'github.com')
    repo = json.loads(run('gh', 'repo', 'view', args.repo,
                          '--json', 'nameWithOwner,viewerPermission,visibility').stdout)
    if repo['nameWithOwner'].lower() != args.repo.lower() or repo['viewerPermission'] not in ('ADMIN', 'MAINTAIN', 'WRITE'):
        raise ValueError('The selected GitHub account cannot write to this repository')
    if args.expected_visibility and repo['visibility'].lower() != args.expected_visibility:
        raise ValueError('Repository visibility differs from the required visibility')
    local_tag = run('git', 'rev-parse', '--verify', f'refs/tags/{TAG}^{{commit}}', check=False)
    if local_tag.returncode == 0 and local_tag.stdout.strip() != commit:
        raise ValueError('The existing local tag refers to a different commit')
    if local_tag.returncode != 0:
        run('git', 'tag', '-a', TAG, '-m', f'Pearl Pocket {VERSION}')
    url = f'https://github.com/{args.repo}.git'
    remote = run('git', 'remote', 'get-url', 'origin', check=False)
    if remote.returncode != 0:
        run('git', 'remote', 'add', 'origin', url)
    elif remote.stdout.strip().removesuffix('.git').lower() != url.removesuffix('.git').lower():
        raise ValueError('The origin remote does not match the selected repository')
    remote_tag = run('git', 'ls-remote', 'origin', f'refs/tags/{TAG}^{{}}', f'refs/tags/{TAG}').stdout
    if remote_tag:
        references = dict(line.split()[::-1] for line in remote_tag.splitlines())
        target = references.get(f'refs/tags/{TAG}^{{}}', references.get(f'refs/tags/{TAG}'))
        if target != commit:
            raise ValueError('The existing remote tag refers to a different commit')
    existing = run('gh', 'release', 'view', TAG, '--repo', args.repo, check=False)
    if existing.returncode == 0:
        raise ValueError('This release already exists; inspect it instead of replacing its assets')
    run('gh', 'auth', 'setup-git', '--hostname', 'github.com')
    run('git', 'push', '--atomic', 'origin', 'main', f'refs/tags/{TAG}')
    created = run('gh', 'release', 'create', TAG, '--repo', args.repo, '--verify-tag',
                  '--prerelease', '--title', f'Pearl Pocket {VERSION}',
                  '--notes-file', str(PROJECT/f'docs/releases/{TAG}.md'),
                  *[str(artifacts/name) for name in ASSETS])
    print(created.stdout.strip())
    release = json.loads(run('gh', 'release', 'view', TAG, '--repo', args.repo,
                            '--json', 'url,assets,isDraft,isPrerelease,tagName').stdout)
    if release['isDraft'] or not release['isPrerelease'] or release['tagName'] != TAG:
        raise ValueError('Published release metadata differs from the intended release')
    remote_assets = {asset['name']: asset for asset in release['assets']}
    for name in ASSETS:
        asset = remote_assets.get(name)
        if not asset or asset['size'] != (artifacts/name).stat().st_size:
            raise ValueError(f'Uploaded asset is missing or has the wrong size: {name}')
        if name != 'SHA256SUMS' and asset.get('digest') and asset['digest'] != 'sha256:' + sums[name]:
            raise ValueError(f'Uploaded asset digest differs: {name}')
    print('Verified GitHub release: ' + release['url'])


if __name__ == '__main__':
    try:
        publish()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f'Publication stopped: {error}', file=sys.stderr)
        sys.exit(1)
