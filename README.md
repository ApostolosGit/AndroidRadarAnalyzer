# AIRCAT500 Mobile v1.0 (AndroidRadarAnalyzer)

Native Kotlin Android application **based on the verified AIRCAT500 Analyzer v9.16 for Windows** and the tested AIRCAT500 Mobile v0.2 radar playback.

## Features (offline v1.0)

- Android document picker loads AIRCAT500 CSV records, CRC-validated raw RS232 SYNC **BSC BIN**, and IPv4 UDP **PCAP/PCAPNG** capture files.
- Native scrolling/zooming/panning 250 NM radar with smooth recorded-sector interpolation, target labels and alerts, sector sweep, recent target trails, and tap-to-inspect Mode A/C and quality details.
- Offline autoplay after file import, Play/Pause, 1×/2×/4×/8×, 1-frame steps, and seek through the original timestamps.
- Monitoring: NORTH, 32 sectors, extension frames, track/CRC summaries and last payload HEX.
- Errors: NORTH-to-NORTH completed cycles, missing/duplicate/out-of-order sectors, NORTH timing, and **non-additive** total unique faults.
- Alerts: Mode A 7500/7600/7601/7700, SPI, emergency flag, cancellation, invalid Mode A/C and low quality.
- Charts: sector traffic, missing sectors, AIRCAT packet types and alert event groups.
- Filters: PSR/MSSR, low quality, cancelled, labels/trails, alert-only and invalid Mode values.
- Save combined plain-text analysis report into Android Downloads via system document picker.

**Not included yet:** Live RICI5000/Ethernet and ESP32-S3 Wi-Fi AP streaming, PC Wireshark integration and direct serial COM/USB modes. These need a separate networking / Android USB implementation and appropriate firmware. A mobile offline port cannot reproduce Windows Tkinter UI pixel-for-pixel.

## Safety / interpretation

- The recorded CSV contains **CRC-valid payloads only**. CRC-failed BSC frames are absent. CSV CRC counters are optional reader-side snapshots, not independently recomputed CRC checks.
- Raw BSC BIN can be CRC checked offline (CRC16/KERMIT) and reports real CRC failure counters; its timestamps are **estimated** from a constant input byte rate of 2400 bytes/s (not wall-clock timestamps).
- Missing sector IDs and suspicious CRC-correlated gaps are distinct diagnostics. Correlation does **not** prove a fault in RADAR cabling or hardware.
- This is analysis/engineering software, not certified ATC display software. Do not use it for operational flight separation decisions.
- Real captures are **not committed** to this public repository.

## Verification with supplied test captures (private)

| Capture | CRC-valid payload rows | NORTH cycles | Valid 32/32 cycles | Missing sector IDs | Unique faults |
|---|---:|---:|---:|---:|---:|
| 20261108_182516 CSV | 3,203 | 94 | 91 | 3 | 3 |
| 20261108_194459 CSV | 5,340 | 159 | 157 | 2 | 2 |
| Raw USB BIN (98,754 bytes) | 343 BSC CRC OK | 9 | 9 | 0 | 0 |

Tests are run by the GitHub Actions **Android Debug APK** workflow, including pure Kotlin JUnit tests. Android API 26+, target API 35. Based on the tested v0.2 package `gr.aircat.androidradaranalyzer`, so the signed debug build should install as an update when GitHub uses the same debug signing key, though otherwise Android may require uninstall/reinstall. Android Studio / Gradle 8.11.1, Android SDK 35, Java 17.

## Offline JVM regression (two locally supplied CSVs)

```bash
kotlinc app/src/main/java/gr/aircat/android/AircatCore.kt \
  app/src/main/java/gr/aircat/android/AircatAnalysis.kt \
  app/src/main/java/gr/aircat/android/AircatBsc.kt \
  app/src/main/java/gr/aircat/android/AircatPcap.kt \
  OfflineRegression.kt -include-runtime -d offline-regression.jar
java -jar offline-regression.jar /path/to/first.csv /path/to/second.csv
```
