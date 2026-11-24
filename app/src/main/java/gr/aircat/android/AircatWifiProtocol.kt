package gr.aircat.android

import java.util.zip.CRC32

data class WifiCounters(
    val received: Long = 0, val duplicates: Long = 0, val lost: Long = 0,
    val invalid: Long = 0, val badCrc32: Long = 0, val v2Packets: Long = 0,
    val firmwareOverflow: Long = 0, val overflowAtConnect: Long? = null,
    val clockHz: Long = 0, val firmwareVersion: String = "unknown",
    val firmwareMode: String = "", val acknowledged: Boolean = false,
    val linkResets: Long = 0
) {
    val overflowSinceConnect: Long get() = (firmwareOverflow - (overflowAtConnect ?: firmwareOverflow)).coerceAtLeast(0)
    val firmwareLabel: String get() = if (firmwareVersion == "unknown")
        "Firmware: μη διαθέσιμη έκδοση" else "Firmware: v$firmwareVersion"
}

data class WifiChunk(val bytes: ByteArray, val resetStream: Boolean)
class WifiBusyException : java.io.IOException("Η συσκευή εξυπηρετεί ήδη άλλη εφαρμογή Wi-Fi. Σταμάτησε εκεί τη λήψη και ξαναδοκίμασε.")

/** AC50 v1/v2, byte-for-byte compatible with desktop v30.5 WifiReceiver. */
class AircatWifiProtocol {
    private var counters = WifiCounters()
    private var lastSequence: Long? = null
    private var lastDataMs: Long? = null
    fun snapshot(): WifiCounters = counters

    fun acceptHello(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.US_ASCII)
        if (text == "AIRCAT_BUSY_V1") throw WifiBusyException()
        if (text != "AIRCAT_ACK_V1" && !text.startsWith("AIRCAT_ACK_V1 ")) return false
        val fields = Regex("([a-z_]+)=([A-Za-z0-9._-]{1,32})(?=\\s|$)")
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        counters = counters.copy(acknowledged = true,
            firmwareVersion = fields["version"] ?: "unknown", firmwareMode = fields["mode"] ?: "")
        return true
    }

    fun rejectSource() { counters = counters.copy(invalid = counters.invalid + 1) }

    fun decode(packet: ByteArray, length: Int = packet.size, nowMs: Long): WifiChunk? {
        fun invalid(): WifiChunk? { rejectSource(); return null }
        if (length < 20 || length > packet.size) return invalid()
        fun u(at: Int) = packet[at].toInt() and 255
        fun le32(at: Int): Long = (0..3).fold(0L) { value, i -> value or (u(at+i).toLong() shl (8*i)) }
        if (u(0)!=65 || u(1)!=67 || u(2)!=53 || u(3)!=48 || u(5)!=0) return invalid()
        val version = u(4)
        if (version !in 1..2) return invalid()
        val size = u(6) or (u(7) shl 8)
        val header = if (version == 2) 24 else 20
        if (size > 384 || length != header + size) return invalid()
        if (version == 2) {
            val crc = CRC32().apply { update(packet,0,20); update(packet,24,size) }.value
            if (crc != le32(20)) {
                counters = counters.copy(badCrc32 = counters.badCrc32 + 1)
                return null // Do not advance sequence; an intact duplicate may follow.
            }
            counters = counters.copy(v2Packets = counters.v2Packets + 1)
        }
        val sequence = le32(8)
        var reset = false
        lastSequence?.let { previous ->
            val diff = (sequence - previous) and 0xffffffffL
            if (diff == 0L || (diff >= 0x80000000L && lastDataMs?.let { nowMs-it < 3000 } == true)) {
                counters = counters.copy(duplicates = counters.duplicates + 1)
                return null
            }
            if (diff in 2 until 0x80000000L) {
                counters = counters.copy(lost = counters.lost + diff - 1)
                reset = true
            } else if (diff >= 0x80000000L) reset = true
        }
        val overflow = le32(12)
        if (overflow > counters.firmwareOverflow) reset = true
        lastSequence = sequence
        lastDataMs = nowMs
        counters = counters.copy(received = counters.received + 1,
            firmwareOverflow = overflow, overflowAtConnect = counters.overflowAtConnect ?: overflow,
            clockHz = le32(16), acknowledged = true,
            linkResets = counters.linkResets + if (reset) 1 else 0)
        return WifiChunk(packet.copyOfRange(header,length),reset)
    }
}
