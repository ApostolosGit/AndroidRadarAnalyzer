# AndroidRadarAnalyzer — AIRCAT500 Mobile (Phase 1)

Native **Android/Kotlin** application for offline playback of verified AIRCAT500
CSV exports from the Windows Analyzer. The repository's initial content is a
small Android Studio project; Wi‑Fi/AP support is intentionally **not** claimed
in this phase.

## Android features

- **Open CSV** with Android's document picker; works completely offline.
- **Radar Replay** with North, 32 sector IDs and decoded PSR/MSSR track points.
- Replay controls: Play/Pause, Seek, +1 Frame, speed 1×/2×/4×/8×.
- Native Canvas radar, 250 NM display radius using the Windows default raw
  scale of **32 units per NM**. No synthetic targets are displayed.
- **Monitoring** view with frame counts, North/sector/extension counts, complete
  and perfect rotations, missing sector IDs, duplicate/out-of-order sector IDs,
  track count, and current HEX payload.
- Reads optional `usb_crc_fail_total` / `usb_framing_error_total` from newer
  CSV files. Their delta is diagnostic only; CSV stores CRC-accepted payloads,
  **not** the original corrupted BSC frames.

## Build in Android Studio

1. Clone or unzip the repository and choose **Open** in Android Studio.
2. Install **Android SDK 35** and use **JDK 17** (or a compatible JDK).
3. Gradle plugin: **8.9.2**, Gradle: **8.11.1**, Kotlin: **2.2.21**.
4. Sync Gradle and run the `app` configuration on Android 8.0+.
5. Tap **Άνοιγμα CSV** and choose an AIRCAT500 Record CSV.

This checkout contains Gradle build files and the distribution URL, but the
binary `gradle-wrapper.jar` is not bundled. Android Studio can manage the
project's Gradle setup; alternatively generate a wrapper with Gradle 8.11.1.

## Verification on supplied captures

The Kotlin parser was compiled and run on the JVM with both real CSV captures:

| File | Payloads | North | Sector | Extension | Complete revolutions | 32/32 perfect | Missing sector IDs | Decoded track elements |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20261108_182516 | 3,203 | 95 | 3,041 | 67 | 94 | 91 | 3 | 12,322 |
| 20261108_194459 | 5,340 | 160 | 5,139 | 41 | 159 | 157 | 2 | 16,057 |

The second CSV carries `usb_crc_fail_total` and `usb_framing_error_total`
deltas of **1 / 1** for its valid-row span, consistent with the separate
`.meta.json` file. This does **not** mean the original CRC-failed frames are
present in the CSV. `missing sector IDs` denotes gaps between two observed
North markers; it does not identify their physical cause.

To re-run these regression checks locally when the two original CSVs are
available (not committed to this public repository):

```bash
kotlinc app/src/main/java/gr/aircat/android/AircatCore.kt OfflineRegression.kt \
  -include-runtime -d offline-regression.jar
java -jar offline-regression.jar /path/to/first.csv /path/to/second.csv
```

You can also run the local JUnit tests from Android Studio.

## Next phase (not yet implemented)

Add a direct Android-to-SYNC2USB Wi‑Fi Access Point data source, ideally with
packet sequence numbers, monotonic timestamps, buffering and explicit loss
counters. The same `RadarPacket` and `RadarReplayState` models can support both
file playback and the future wireless source.

**Security:** Do not commit actual operational radar captures to a public repository.