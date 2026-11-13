# AIRCAT500 Mobile v2.2 (AndroidRadarAnalyzer)

Native Kotlin Android application **based on the verified AIRCAT500 Analyzer v9.16 for Windows** and the tested AIRCAT500 Mobile v0.2 radar playback.

## v2.2 fixes (over v2.1)

- Android APK version name **2.2** (not 2.2.0) and downloadable APK filename **AIRCAT500_V2.2.apk**.
- Default menu/report/summary text **16sp**, independently adjustable **13–20sp**.
- Complete **Errors** and **Alerts** contents scroll correctly; those views no longer inherit the 3-line title truncation.
- Full report starts with a single v2.2 title followed **immediately by capture filename**; removed preambles and limited separator rules to **50 characters**.
- Chart data, colours and zoom remain unchanged; only the **X-axis labels** are drawn farther below the axis.
- Permanent blue range rings at **5-NM intervals up to 220 NM by default**. Settings slider chooses outermost ring from **5–500 NM** in 5-NM steps; NM labels remain anchored to axes.
- **Reset** re-centres the radar and restores **20 NM** without changing replay time or playback state.
- **Radar Zoom +/−** buttons are embedded in its top-right corner. Two-finger pinch zoom has been disabled; dragging labels and panning radar remain.
- Previous v2.1 target retention, labels, decoding, report sharing and replay are retained.

## v2.1 improvements (over v2.0)

- **Single file-load percentage** in the horizontal progress display, followed by explicit analysis and report stages.
- **Independent fonts:** all application menus, charts, errors/alerts summaries and reports default to **13sp**, adjustable 13–16sp; aircraft labels use a separate slider (7–16sp) with 9sp initial. Preferences are saved.
- **Aircraft labels:** separate line per enabled Mode A, Mode C, Speed, Q, etc; tap and drag the actual label to relocate it. Track Number is unchecked initially.
- **Scan persistence:** NORTH starts another rotation without clearing the plot. Each 00–1F sector replaces only its own aircraft on arrival; moved tracks are de-duplicated; explicit cancellations remove aircraft immediately.
- **Radar:** light-grey background and persistent blue rings at exactly 5, 10, 15, 20, 50, 100, 150 and 200 NM. NM numbers rendered on visible X and Y ring-axis intersections using the aircraft-label font size.
- **Replay controls:** `<<`, Play, `>>`, speed and **Reset** on the upper row; **−1 Frame** and **+1 Frame** underneath.
- **Errors/Alerts:** retains the v2 numeric desktop-inspired summaries. Separate reference screenshots must be supplied before pixel-/field-accurate matching with the Python desktop interface can be claimed.
- Does not alter live transport support, target symbology Δ/U/○, decoder wire format, report sharing or desktop chart modes.

## v2.0 additions (over v1.0.2)

- **Aviation UI:** light-blue menus and buttons, light-grey radar, unchanged black Δ/U/○ aircraft symbols and persistent red range rings.
- **Settings:** same top row as Open / Filters / Report; initial 20 NM, configurable maximum radar range (default 250 NM), aircraft label text size, About, optional pause on NORTH/sector errors.
- **Desktop labels:** individually selectable Mode A, Mode C, Track Number, Speed (kt), Range (NM), Quality Q, detection misses, TPR, LogSigma, time since NORTH, tracking window, smoothing K and relative velocity-vector cue. Speed Label defaults OFF, Mode A/C defaults ON.
- **File loading:** actual document-byte progress from 0–75% when provider exposes file length, then analysis, chart/report generation stages up to 100%.
- **Charts:** initial desktop-style 35-column stacked North/Extension/Weather/00..1F chart (blue Correct, red Sector missing, pink Sector without previous North), Zoom Peak / Zoom Out, swipe and pinch for small screens. Mobile available decoder statistics are used; some desktop counters are not fully equivalent.
- **Errors / Alerts:** extended numeric summary, missing sectors by ID, NORTH cycle detail, per-source alert and validation summaries.
- **Report:** full capture-derived report opened on import, also available under Report, with Save TXT and Android share sheet (Viber if installed).
- **Replay / RAW:** forward/backwards 1×–16×, time scrub, Reset/Step Back, optional pause at NORTH errors, one-touch Current Scan RAW in Monitor.
- Does **not** change label dragging or introduce new emergency/alert symbology. **Not certified for operational ATC use.**

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
