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
normalized=text.replace('v2.3.1','v2.2').replace('V2.3.1','V2.2')
normalized=normalized.replace('private fun chooseFile(suggestWifi: Boolean = true)', 'private fun chooseFile()')
normalized=normalized.replace('        if (suggestWifi && offerLastWifiRecording()) return\n','')
normalized=normalized.replace('            wifiRecordingHistory.latest()?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,it) }\n','')
# Rejoin the unchanged import/analysis body after extracting it for explicit replay/analysis choices.
normalized=normalized.replace('        loadReplayFile(uri)\n    }\n\n    private fun loadReplayFile(uri: Uri,autoPlay: Boolean = true,showAnalysis: Boolean = true) {\n        if (wifiDialog?.isShowing == true) return\n','')
normalized=normalized.replace('                    if (autoPlay) start()\n                    if (showAnalysis) showReport()',
    "                    start() // Keep v0.2's successful autoplay.\n                    showReport() // Python-style full analysis is available immediately.")
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
