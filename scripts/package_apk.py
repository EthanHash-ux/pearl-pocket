#!/usr/bin/env python3
"""Sign a local non-debuggable development APK with a persistent private build key.
The key/password stay outside the project and are never included in artifacts.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default='/opt/pearl-android-sdk')
parser.add_argument('--signing-dir', type=Path, default=Path.home()/'.pearl-wallet-build'/'signing')
args = parser.parse_args()
project = Path(__file__).resolve().parent.parent
(project/'artifacts').mkdir(parents=True, exist_ok=True)
private = args.signing_dir
private.mkdir(parents=True, exist_ok=True, mode=0o700)
private.chmod(0o700)
credentials = private/'credentials.json'
if credentials.exists():
    password = json.loads(credentials.read_text())['password']
else:
    password = secrets.token_urlsafe(36)
    fd = os.open(credentials, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump({'password': password}, out)
env = dict(os.environ, PEARL_SIGN_PASSWORD=password)
keystore = private/'pearl-wallet-development.jks'
if not keystore.exists():
    subprocess.run(['keytool','-genkeypair','-keystore',str(keystore),'-storetype','JKS',
                    '-storepass:env','PEARL_SIGN_PASSWORD','-keypass:env','PEARL_SIGN_PASSWORD',
                    '-alias','pearl-wallet','-keyalg','RSA','-keysize','3072','-validity','3650',
                    '-dname','CN=Pearl Wallet Development, O=Independent Android Project, C=CN', '-noprompt'],env=env,check=True,capture_output=True)
keystore.chmod(0o600)
build_tools = Path(args.sdk)/'build-tools'/'35.0.0'
unsigned = project/'app/build/outputs/apk/release/app-release-unsigned.apk'
aligned = project/'artifacts/pearl-wallet-aligned.apk'
output = project/'artifacts/pearl-wallet-android.apk'
subprocess.run([str(build_tools/'zipalign'),'-P','16','-f','4',str(unsigned),str(aligned)],check=True)
subprocess.run([str(build_tools/'apksigner'),'sign','--ks',str(keystore),'--ks-key-alias','pearl-wallet',
                '--ks-pass','env:PEARL_SIGN_PASSWORD','--key-pass','env:PEARL_SIGN_PASSWORD',
                '--out',str(output),str(aligned)],env=env,check=True)
aligned.unlink()
result = subprocess.run([str(build_tools/'apksigner'),'verify','--verbose','--print-certs',str(output)],check=True,capture_output=True,text=True)
(project/'artifacts/apk-signature.txt').write_text(result.stdout)
print('Signed non-debuggable development APK: ' + str(output))
print('Persistent build key directory: ' + str(private))
