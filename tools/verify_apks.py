"""Verify both installation identities and signatures of the CI-built APKs."""
import hashlib
import os
from pathlib import Path
import re
import subprocess

tools=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
apks={
    'AIRCAT500_V2.3.1.apk':'gr.aircat.androidradaranalyzer',
    'AIRCAT500_V2.3.1_parallel.apk':'gr.aircat.androidradaranalyzer.wifi23',
}
lines=[]
for name,package in apks.items():
    output=subprocess.check_output([str(tools/'aapt'),'dump','badging',name],text=True)
    assert f"package: name='{package}'" in output,name
    assert "versionCode='10'" in output and "versionName='2.3.1'" in output,name
    assert "sdkVersion:'26'" in output and "targetSdkVersion:'35'" in output,name
    subprocess.run([str(tools/'apksigner'),'verify',name],check=True)
    digest=hashlib.sha256(Path(name).read_bytes()).hexdigest()
    lines.append(f'{digest}  {name}')
    print(f'PASS: {name}, package={package}, version=2.3.1, SDK=26..35, signature verified')
Path('AIRCAT500_V2.3.1_SHA256SUMS.txt').write_text('\n'.join(lines)+'\n')
