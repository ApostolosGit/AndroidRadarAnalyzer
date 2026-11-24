# AIRCAT500 Mobile v3.0 – LivePlay Edition (Android)

## Απαράβατο σημείο αναφοράς

Η **επίσημη v2.2** είναι το release
https://github.com/ApostolosGit/AndroidRadarAnalyzer/releases/tag/v2.2
στο commit `5d9f5e9e373a2b31a83b76987dadd798a47e4492`.
Το `AIRCAT500_V2.2.apk`, το `AIRCAT500_V2.2_AndroidStudio.zip`,
το tag και ο κώδικας `feature/mobile-v2-2-display-fixes` **δεν τροποποιούνται**.

Η παρούσα προσθήκη έχει δικό της branch
`feature/mobile-v3-0-liveplay` και παράγει **ξεχωριστό APK**
`AIRCAT500_V3.0_LivePlay_parallel.apk`, με Android package
`gr.aircat.androidradaranalyzer.liveplay`. Εγκαθίσταται δίπλα στο
αυθεντικό v2.2 χωρίς απεγκατάσταση, αλλαγή υπογραφής του αρχικού APK
ή μεταφορά των ιδιωτικών ρυθμίσεων. Το νέο APK παραμένει debug-signed
μέχρι να υπάρξει κανονική έκδοση.

## Ακριβώς η ζητούμενη ροή

**Άνοιγμα** → εμφανίζει **μόνο δύο επιλογές**:

1. **Από Αρχείο** → καλεί την *αρχική* μέθοδο `chooseFile()` της v2.2.
   Δεν έγινε καμία αλλαγή στο import CSV/BIN/PCAP/PCAPNG, offline replay,
   Radar, Monitor, Errors, Alerts, Charts, φίλτρα, report, zoom ή controls.
2. **LivePlay** → εμφανίζει **μόνο δύο επιλογές**:
   - **Live Radar Display**
   - **Radar Serial Line Monitoring**

Η επιλογή ανοίγει ανεξάρτητο παράθυρο LivePlay με αυτές τις **δύο**
εναλλάξιμες καρτέλες. Δεν προσθέτει Live Errors, Alerts, Charts,
CSV Record, live report ή άλλες οθόνες. Οι παλιές αντίστοιχες
λειτουργίες **Από Αρχείο παραμένουν ως είχαν**.

Το Live Radar Display χρησιμοποιεί **τον ίδιο RadarView, τον ίδιο
RadarReplayState και τον ίδιο decoder RadarPacket/AircatCsv.tracks
της v2.2**, χωρίς παραλλαγή σε σύμβολα/στόχους/κλίμακα.
Προσφέρει zoom +/−/Reset και tap-to-inspect. Το Radar Serial Line
Monitoring παρουσιάζει ζωντανά CLOCK, CRC16, framing, NORTH,
sectors, completed/perfect rotations, packet duplication/loss,
UDP CRC32 και firmware overflow. Τα δεδομένα περνούν από τα ίδια
πρωτόκολλα και το CRC απορρίπτει κατεστραμμένα frames.

Το LivePlay είναι ανεξάρτητο: δεν ξαναφορτώνει ούτε αλλοιώνει το
ανοιγμένο offline αρχείο και δεν αλλάζει τον υπάρχοντα χειρισμό
Play/Pause/Seek όταν ο χρήστης επιλέγει **Από Αρχείο**.

## Port δικτύωσης από το υπάρχον Android έργο

Μόνο τα αναγκαία αρχεία από το ήδη υπάρχον
`feature/mobile-v2-3-wifi-live` χρησιμοποιήθηκαν:

- `AircatWifiProtocol.kt`: AC50 v1/v2, CRC32, αναγνώριση διπλοτύπων,
  χαμένων πακέτων και firmware ring overflow.
- `AircatBscStream.kt`: streaming BSC, DLE, CRC16/KERMIT, NORTH και
  sector diagnostics. Χρησιμοποιεί **τον υπάρχοντα v2.2 decoder**.
- `AircatWifiSession.kt`: UDP HELLO/ACK, socket binding, live buffers,
  αναμονή και σύνδεση. Έχουν αφαιρεθεί οι επιπλέον
  `CSV Record`/`WifiRecentCapture` λειτουργίες της άλλης έκδοσης.
- `WifiNetworkConnection.kt`: Android 10+ σύνδεση στο τοπικό AP με
  WifiNetworkSpecifier ή επιλογή «Τρέχον Wi-Fi» για ήδη συνδεδεμένη
  συσκευή, και τοπική δέσμευση UDP sockets.
- `LivePlayDialog.kt`: **νέο, περιορισμένο UI**, χωρίς τις επιπλέον
  καρτέλες της v2.3.

Μόνη αλλαγή στον αρχικό `MainActivity.kt`: ο `Άνοιγμα` δρομολογείται
στον μικρό επιλογέα πηγής, προστίθεται ο επιλογέας των δύο LivePlay
οθονών και ο χειρισμός Android Wi-Fi permission/cleanup. Η πρωτότυπη
`chooseFile()` μαζί με την **ολόκληρη** offline υλοποίηση παραμένει
byte-for-byte ίδια. Τα `AircatCore`, `AircatAnalysis`, `RadarView`,
`ChartView`, `AircatBsc`, `AircatPcap` κ.λπ. δεν άλλαξαν.

Για την πρόσβαση στο Wi-Fi προστέθηκαν μόνο οι απαιτούμενες άδειες
στο AndroidManifest. Η αλλαγή `applicationId`/version στο Gradle
είναι αποκλειστικά για **παράλληλη εγκατάσταση**, όχι για αντικατάσταση
του αρχικού v2.2 APK. Το versionName είναι `3.0`, versionCode `10`.
Η νέα ονομασία εμφανίζεται στην αρχική οθόνη και στο About, το οποίο
αναφέρει πλέον τη λειτουργία Wi-Fi LivePlay. Τα ονόματα και το περιεχόμενο
των offline reports παραμένουν ακριβώς όπως στην v2.2. Το προηγούμενο
δοκιμαστικό LivePlay APK χρησιμοποιεί το ίδιο package ID· η ενημέρωσή
του απαιτεί συμβατή υπογραφή ή απεγκατάστασή του, όχι της αυθεντικής v2.2.

Ο `tools/verify_v22_liveplay_only.py` επαληθεύει τις **ακριβείς Git
blob SHA1 και των 25 αρχικών αρχείων** της επίσημης v2.2. Αναστρέφει
μηχανικά τα πολύ συγκεκριμένα πρόσθετα hooks στα 3 επιτρεπόμενα
αρχεία (MainActivity, manifest, Gradle) και ελέγχει ότι το αποτέλεσμα
είναι **ακριβώς** το original byte sequence. Απορρίπτει άγνωστες
προσθήκες και επιπλέον LivePlay tabs.

## Συμβατότητα με ESP32-S3 v40.0

- Το SSID του firmware είναι `SYNC`.
- Ο κωδικός Wi-Fi είναι αυτός που έχεις ορίσει στο `AP_PASSWORD`
  του `SYNC2USB_WIFI_v40_0.ino` (δεν αποθηκεύεται στον Android client).
- IP `192.168.4.1`, UDP data **5005**, HELLO/ACK **5006**.
- Η εφαρμογή στέλνει `AIRCAT_HELLO_V1 <PIN>` ανά 2s.
  Το default `WIFI_PIN` είναι `1234`, αν δεν έχει τροποποιηθεί.
- Το firmware v40.0 απαντά **`AIRCAT_ACK_V1` χωρίς version**. Το
  Android το αποδέχεται σωστά και αναφέρει
  **«Firmware: μη διαθέσιμη έκδοση»**. Δεν εφευρίσκει firmware v40.0.
  Τα 19.200 Hz παρέχονται στην κεφαλίδα AC50 v2.
- Το AC50 v2 packet έχει header 24 bytes, sequence, firmware overflow
  και clockHz, CRC32· κάθε payload στέλνεται δύο φορές και το Android
  αγνοεί τα duplicate copies. Όταν διαπιστωθεί gap/overflow,
  επανασυγχρονίζει τον συνεχή BSC parser.
- Σύνδεση στο SSID δεν αρκεί: το Wi-Fi UDP stream ενεργοποιείται
  μόνον έπειτα από αποδεκτό PIN/HELLO. Η απλή εκκίνηση AP χωρίς client
  δεν προκαλεί αποστολή radar bytes.
- Android 10+ ζητά επιβεβαίωση σύνδεσης στο τοπικό δίκτυο.
  Android 13+ πιθανόν άδεια Nearby Wi-Fi Devices· Android 10–12
  απαιτεί την αντίστοιχη άδεια Location του Android. Δεν γίνεται
  ανάκτηση γεωγραφικής θέσης από την εφαρμογή. Android 8/9: συνδέσου
  χειροκίνητα στο SYNC και επέλεξε «Τρέχον Wi-Fi».
- Το AP είναι χωρίς Internet. Αν το Android προτείνει να φύγει από
  το δίκτυο, διατήρησε τη σύνδεση SYNC για το LivePlay.

## Δοκιμή

1. Κράτησε εγκατεστημένη την **επίσημη AIRCAT500 Mobile v2.2**.
2. Εγκατάστησε το **παράλληλο LivePlay APK** από τα artifacts του
   GitHub Actions της νέας διακλάδωσης.
3. Στο νέο app: **Άνοιγμα → Από Αρχείο**: έλεγξε ότι κάθε αρχείο
   ανοίγει ακριβώς όπως στην v2.2.
4. **Άνοιγμα → LivePlay → Live Radar Display**. Αν το κινητό έχει
   ήδη συνδεθεί στο SYNC, διάλεξε «Τρέχον Wi-Fi», αλλιώς
   «Σύνδεση SYNC» και πρόσθεσε τον σωστό κωδικό AP/PIN.
5. Βεβαιώσου ότι εμφανίζονται ζωντανά sector sweep και στόχοι.
6. Επίλεξε **Radar Serial Line Monitoring** και έλεγξε
   CRC, framing, UDP CRC32, duplicates, lost και overflow.
7. Κλείσε το LivePlay· τα αρχεία/replay της αρχικής v2.2 εφαρμογής
   είναι ανεπηρέαστα.

## Έλεγχοι και περιορισμοί

Το CI τρέχει: (α) ακριβή διατήρηση v2.2 blobs, (β) Android/Kotlin
JUnit tests για AC50 v2, CRC32, BSC16, UDP loopback/HELLO,
streaming και bounded buffer, και (γ) δημιουργία APK.

Η πραγματική σύνδεση **με το συγκεκριμένο κινητό και ESP32-S3 v40.0**
δεν επαληθεύεται από τα CI tests. Χρειάζεται δοκιμή σε φυσική συσκευή.
Το εργαλείο δεν είναι πιστοποιημένο για επιχειρησιακό έλεγχο εναέριας
κυκλοφορίας.
