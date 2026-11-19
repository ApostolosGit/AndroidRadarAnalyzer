"""Guard the existing v2.2 engine, UI and offline replay against unrelated edits."""
import hashlib
import json
from pathlib import Path
import re

root=Path(__file__).resolve().parents[1]
baseline=json.loads((root/'tools/v22_baseline.json').read_text())
for name,expected in baseline['files'].items():
    data=(root/name).read_bytes()
    actual=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    assert actual==expected,f'Unrelated v2.2 source/test changed: {name}'
text=(root/'app/src/main/java/gr/aircat/android/MainActivity.kt').read_text()
normalized=text.replace('v2.3','v2.2').replace('V2.3','V2.2')
normalized=re.sub(r'^.*wifiDialog\?\.applySettings\(\)\n','',normalized,flags=re.M)
normalized=normalized.replace('        root.addView(row(button("Wi-Fi / SYNC2WIFI") { showWifiWindow() }, height = 36))\n','')
normalized=normalized.replace('        destroying = true\n        wifiDialog?.dismiss()\n','')
normalized=re.sub(r'\n        if \(requestCode == 79\).*?(?=\n        if \(requestCode == 78\))','',normalized,flags=re.S)
for name,expected in baseline['main'].items():
    if name=='showAbout':continue # Deliberately describes the new WiFi capability.
    start=re.search(r'^    (?:private |override )?fun '+name+r'\(',normalized,re.M).start()
    next=re.search(r'\n    (?:private fun |override fun |private val ticker\b)',normalized[start+1:])
    actual=normalized[start:] if next is None else normalized[start:start+1+next.start()]
    assert actual==expected,f'Offline v2.2 behavior changed beyond the WiFi hooks: {name}'
assert text[text.index('    private val ticker'):text.index('    private fun updateSweep')]==baseline['ticker']
print(f"PASS: {len(baseline['files'])} existing source/test files unchanged; v2.2 methods and replay ticker preserved")
