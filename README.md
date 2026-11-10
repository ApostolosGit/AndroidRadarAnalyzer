# AIRCAT500 Mobile v1.1.0 — Android Radar Analyzer

Native **Kotlin Android** offline radar analyzer based on the AIRCAT500 Windows Python v9.16 decoder and the verified Mobile v0.2 replay UI.

## What's new in v1.1

- Light-gray radar screen (#EAEDF0) and black target symbols: **Δ MSSR/SSR**, **U combined PSR+SSR**, **○ PSR-only**.
- Target annotations show **Mode A and Mode C by default on every drawn target**. Missing fields appear as `----` (not fabricated Mode A/C codes). Also optionally display track number, speed (kt), range (NM), quality and velocity vectors.
- Target presentation **does not automatically blank on NORTH** and is frozen on **Pause**; the previous display is cleared when **Play** starts/resumes. New sectors refresh their corresponding targets while playback runs. Seeking reconstructs the state at the requested recorded time.
- True **time-based seek bar**, with elapsed / total capture time. **◀◀** reverses playback and **▶▶** plays forward; Play/Pause, 1/2/4/8x, previous/next-frame and Reset Zoom.
- **Filters** for separate PSR/MSSR/Combined categories, min/max **altitude (ft)** and min/max **speed (kt)**, plus low-quality, cancellation, invalid Mode A/C, alert, labels, trails, quality/range/speed overlays. Missing Mode C or speed does **not** pass an active numeric filter.
- Keeps v1.0 offline CSV, RAW BSC BIN, PCAP/PCAPNG imports, Monitor, Errors, Alerts, Charts and report export. The CSV picker remains unrestricted by provider MIME type.
- Sparse 128-frame snapshots keep reverse playback and random seeking responsive without inventing targets.

## Verification

The pure Kotlin parser / state / checkpoint regression passed with real captures:

| Input | Valid packets | Complete NORTH cycles | Missing sector IDs |
| --- | ---: | ---: | ---: |
| `AIRCAT500_SYNC2USB_USB_Record_20261108_182516.csv` | 3,203 | 94 | 3 |
| `AIRCAT500_SYNC2USB_USB_Record_20261108_194459.csv` | 5,340 | 159 | 2 |

The raw capture files are **not** included in the public GitHub repository.

Build with Android Studio (API 26+, target SDK 35), or run `gradle :app:testDebugUnitTest :app:assembleDebug` using Gradle 8.11.1 and JDK 17.

This version is **offline**. Live Wi-Fi AP / SYNC2USB, Bluetooth and physical USB acquisition are not included yet. Device compatibility and visual acceptance still require user testing on a physical Android handset.


## v1.2 — Aviation-blue UI and desktop-like summaries
- Deeper gray PPI; red concentric range rings with real NM labels; outer range = 250 NM / zoom.
- Track number label OFF by default, Mode A/C ON. Tracks retain a 10-position trail by default.
- Dynamic stacked sector histogram with Correct (blue), Missing (red), Without previous NORTH (pink);
  Zoom Peak enabled, horizontal scrolling on a phone.
- Errors and Alerts summary tables, broken down by PSR/MSSR/Other where possible.
- Report button explains exactly what it writes before opening the Android save dialog.
- Live SYNC2USB Wi-Fi is not yet included; offline CSV/BIN/PCAP replay remains available.
- Unknown prefix/malformed diagnostics unavailable from the current mobile parser are shown as “—” rather than invented zeros.
