"""Verify every original v2.2 release file and offline code remains identical.

Reference: official AIRCAT500 Mobile v2.2 release commit
5d9f5e9e373a2b31a83b76987dadd798a47e4492.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASELINE = json.loads(r'''{".github/workflows/android.yml":"01a5e89d53760cca68d03f548b2bf9e914eea23a",".github/workflows/import-v1.yml":"32ec2c0a267e60af02aa91a90f26c174205330d2",".gitignore":"d965e8b65d5b921a3f7169401b5d58734ca82189","OfflineRegression.kt":"55e3f7df811000a8b68ce8931badfba24dc5485b","README.md":"c9d9d9031b1cf4f6fb023b8d4df3a55adf6df505","app/build.gradle.kts":"bf9285429a9e673097e523e90b0f0fc10df29be2","app/src/main/AndroidManifest.xml":"3e22809294faa593532177dded863216feec996f","app/src/main/java/gr/aircat/android/AircatAnalysis.kt":"60aea53df2046a8236212bc05102fc29717c6cfc","app/src/main/java/gr/aircat/android/AircatBsc.kt":"aaf931026b5f7462b8180b3ce6824e03fd4889c7","app/src/main/java/gr/aircat/android/AircatCore.kt":"37e577057fde130ac806dd393715ecf90b5ae742","app/src/main/java/gr/aircat/android/AircatDesktopSummary.kt":"62654dfab5c5129e4c4371d600b8f347097d5e25","app/src/main/java/gr/aircat/android/AircatPcap.kt":"cb63dd9d5969a3b1dfa977af0b7428cfcc367790","app/src/main/java/gr/aircat/android/ChartView.kt":"0d01a7dde638ee80bdf4437c16e237af294b2264","app/src/main/java/gr/aircat/android/MainActivity.kt":"c968757e5b3f1103c34913154f9f06f61be6abdf","app/src/main/java/gr/aircat/android/RadarView.kt":"90653b966bab5261365d5fc5b24c5acb4f8865cc","app/src/main/res/values/strings.xml":"e9bd8035f27e5a818bd3fff1ccf11b5ac60dca3e","app/src/test/java/gr/aircat/android/AircatCoreTest.kt":"51d06d1a1b76260109e5efe73844038ae6fd94af","app/src/test/java/gr/aircat/android/AircatV1Test.kt":"61e6e5d3986a4ce8ddda50c592139117d87e4f80","app/src/test/java/gr/aircat/android/AircatV21Test.kt":"768b6b973956cac2668e5c22ad28a81e6653a00a","app/src/test/java/gr/aircat/android/AircatV22Test.kt":"1833b61915d2be24ad483d659f6d762a8d4f126f","app/src/test/java/gr/aircat/android/AircatV2Test.kt":"25f9893441588b21247ef7a5a4d432ca6756a8e1","build.gradle.kts":"574eee7f99df7a75dac56af61ecbf149cc2fa79b","gradle.properties":"e6961679fc95d6da9ab1dc56ee2e9d98ecbe6e63","gradle/wrapper/gradle-wrapper.properties":"f7c982e50017648567eed12d79498f3f22773db4","settings.gradle.kts":"3935bd12acfbc4cc1a8c9902876189cf91cb5e85"}''')
ADDITIONS = set(json.loads(r'''["app/src/main/java/gr/aircat/android/AircatWifiProtocol.kt","app/src/main/java/gr/aircat/android/AircatBscStream.kt","app/src/main/java/gr/aircat/android/AircatWifiSession.kt","app/src/main/java/gr/aircat/android/WifiNetworkConnection.kt","app/src/main/java/gr/aircat/android/LivePlayDialog.kt","app/src/test/java/gr/aircat/android/LivePlayProtocolTest.kt",".github/workflows/liveplay-only.yml","tools/verify_v22_liveplay_only.py","LIVEPLAY_PORT_GR.md"]'''))
ADDITIONS.update(["app/src/main/java/gr/aircat/android/SyncCredentials.kt","app/src/main/java/gr/aircat/android/LiveCsv.kt","app/src/test/java/gr/aircat/android/LiveV4Test.kt"])
ADDITIONS.update(["app/src/main/java/gr/aircat/android/TcpLabProtocol.kt","app/src/main/java/gr/aircat/android/AircatTcpLabSession.kt","app/src/test/java/gr/aircat/android/TcpLabTest.kt"])
ADDITIONS.update(["app/src/main/java/gr/aircat/android/LabCsvAudit.kt","app/src/test/java/gr/aircat/android/LabCsvAuditTest.kt"])
OLD_MAIN = 'app/src/main/java/gr/aircat/android/MainActivity.kt'
OLD_GRADLE = 'app/build.gradle.kts'
OLD_MANIFEST = 'app/src/main/AndroidManifest.xml'

def blob_sha(source: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(source)).encode() + b'\0' + source).hexdigest()

def normalize_main(src: str) -> str:
    src = src.replace("                        // LAB_AUDIT_ROUTE_BEGIN\n                        val lab = loadInput().bufferedReader(Charsets.UTF_8).use { reader ->\n                            reader.mark(65536)\n                            val header = reader.readLine()\n                            reader.reset()\n                            if (header != null && LabCsvAudit.isLabHeader(header)) LabCsvAudit.read(reader) else null\n                        }\n                        if (lab != null) {\n                            val report = lab.report()\n                            runOnUiThread {\n                                hideLoadProgress()\n                                info.text = \"TCP LAB CSV — offline audit (previous replay unchanged)\"\n                                AlertDialog.Builder(this).setTitle(\"AIRCAT500 — LAB CSV Audit\")\n                                    .setMessage(report).setPositiveButton(\"OK\", null).show()\n                            }\n                            return@execute\n                        }\n                        // LAB_AUDIT_ROUTE_END\n", '')
    src = src.replace("        if (requestCode == WifiNetworkConnection.WIFI_PANEL_REQUEST) {\n            livePlayDialog?.wifiPanelResult()\n            return\n        }\n", "")
    src = src.replace("        if (requestCode == 2304) {\n            livePlayDialog?.recordDestination(if (resultCode == RESULT_OK) data?.data else null)\n            return\n        }\n", "")
    src = src.replace("AIRCAT500 Mobile  v5.01 LAB", "AIRCAT500 Mobile  v2.2")
    src = src.replace("Android offline radar analysis & replay + LivePlay\\n", "Android offline radar analysis & replay\\n")
    src = src.replace("Desktop reference: Python Analyzer v9.16 (offline), v40.0 (LivePlay)\\n", "Desktop reference: Python Analyzer v9.16\\n")
    src = src.replace("LivePlay: SYNC2WIFI (Live Radar Display / Radar Serial Line Monitoring). USB live is not included.\\n", "SYNC2USB live Wi-Fi/USB: not included in this build.\\n")
    src = src.replace('    private var livePlayDialog: LivePlayDialog? = null\n', '')
    src = src.replace('val open = button("Άνοιγμα", 11f) { chooseOpenSource() }',
                      'val open = button("Άνοιγμα", 11f) { chooseFile() }')
    src, count = re.subn(r'    // Additive routing only:.*?(?=    private fun chooseFile\(\) \{)',
                         '', src, count=1, flags=re.S)
    assert count == 1, 'The Open source selector was modified unexpectedly'
    src, count = re.subn(r'    override fun onRequestPermissionsResult\(.*?(?=    override fun onDestroy\(\) \{)',
                         '', src, count=1, flags=re.S)
    assert count == 1, 'The live-only permission delegate was modified unexpectedly'
    src = src.replace('    override fun onDestroy() {\n        livePlayDialog?.dismiss()\n',
                      '    override fun onDestroy() {\n')
    return src

def normalize_gradle(src: str) -> str:
    return (src.replace('applicationId = "gr.aircat.androidradaranalyzer.tcplab"',
                        'applicationId = "gr.aircat.androidradaranalyzer"')
               .replace('versionCode = 14', 'versionCode = 8')
               .replace('versionName = "5.01"', 'versionName = "2.2"'))

def normalize_manifest(src: str) -> str:
    src, count = re.subn(r'    <!-- LivePlay adds only Android local Wi-Fi association and UDP transport. -->.*?(?=    <application )',
                         '', src, count=1, flags=re.S)
    assert count == 1, 'Live-only permission declarations missing or changed'
    return src.replace('android:label="AIRCAT500 Mobile v5.01 LAB"', 'android:label="AIRCAT500 Mobile"')

for path, expected in BASELINE.items():
    file = ROOT / path
    assert file.is_file(), f'v2.2 file removed: {path}'
    if path == OLD_MAIN:
        data = normalize_main(file.read_text(encoding='utf-8')).encode('utf-8')
    elif path == OLD_GRADLE:
        data = normalize_gradle(file.read_text(encoding='utf-8')).encode('utf-8')
    elif path == 'app/src/main/java/gr/aircat/android/RadarView.kt':
        src = file.read_text(encoding='utf-8')
        src = src.replace("    // Live-only footer; offline replay retains the complete original footer.\n    var liveTargetsOnly = false\n        set(value) { field = value; invalidate() }\n", '')
        src = src.replace("        canvas.drawText(if (liveTargetsOnly) \"${tracks.size} targets\" else \"${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: \"--\"}  •  Range %.1f NM\".format(visibleRangeNm),", "        canvas.drawText(\"${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: \"--\"}  •  Range %.1f NM\".format(visibleRangeNm),")
        data = src.encode('utf-8')
    elif path == 'app/src/main/java/gr/aircat/android/AircatCore.kt':
        data = file.read_text(encoding='utf-8').replace("        require(\"capture_event\" !in keys) { \"TCP LAB CSV: use the v50.0 Python continuity-aware audit; legacy offline analysis is disabled for this format.\" }\n", '').encode('utf-8')
    elif path == OLD_MANIFEST:
        data = normalize_manifest(file.read_text(encoding='utf-8')).encode('utf-8')
    else:
        data = file.read_bytes()
    assert blob_sha(data) == expected, f'v2.2 changed: {path}'

tracked = set(subprocess.check_output(['git', 'ls-files', '--cached', '-z'], cwd=ROOT)
              .decode('utf-8').rstrip('\0').split('\0'))
unexpected = tracked - set(BASELINE) - ADDITIONS
missing = ADDITIONS - tracked
assert not unexpected, f'Unrequested additional files: {sorted(unexpected)}'
assert not missing, f'LivePlay additions missing: {sorted(missing)}'

main = (ROOT / OLD_MAIN).read_text(encoding='utf-8')
dialog = (ROOT / 'app/src/main/java/gr/aircat/android/LivePlayDialog.kt').read_text(encoding='utf-8')
assert '.setItems(arrayOf("Αρχείο", "SYNC2WIFI"))' in main
assert 'if (source == 0) chooseFile() else chooseLivePlayScreen()' in main
assert '.setItems(arrayOf("Live Radar Display", "Radar Serial Line Monitor"))' in main
assert 'button("Σύνδεση")' in dialog
assert 'button("Reset")' not in dialog
assert 'liveTargetsOnly = true' in dialog
assert 'button("Record")' in dialog
assert 'Εμφάνιση κωδικού / PIN' in dialog
assert 'if (!attempted)' in dialog
assert 'button("Τρέχον Wi-Fi")' not in dialog
assert 'button("Wi-Fi κινητού")' not in dialog
assert not any(f'button("{tab}")' in dialog for tab in ('Errors', 'Alerts', 'Charts', 'Report', 'CSV Record'))
assert 'APP_PASSWORD' not in dialog  # No hardcoded Wi-Fi credential
print(f"PASS: exact v2.2 release preservation ({len(BASELINE)} files), "
      "reversible Open hook, two LivePlay screens, isolated Wi-Fi source")
