package gr.aircat.android

import java.io.StringReader
import java.util.Locale

/**
 * AIRCAT500 RS232 SYNC transparent BSC reader matching the Python 9.16 BSCObserver.
 * CRC-16/KERMIT reflected 0x8408, initial 0, final XOR 0, wire low byte first.
 * CRC covers TYPE, ADDR, DLE STX, destuffed inner payload and ETX.
 */
data class BscStatus(
    val byteCount: Int, val sync: Int, val valid: Int, val crcFailed: Int,
    val framingFailed: Int, val rejected: Int
)

data class BscImport(val capture: RadarCapture, val status: BscStatus)

object AircatBsc {
    private val TYPES = setOf(0x0C, 0x0D, 0x19, 0x1C, 0x2A, 0x0E)
    private fun u(value: Byte) = value.toInt() and 255

    fun crc16Kermit(payload: ByteArray): Int {
        var crc = 0
        for (byte in payload) {
            crc = crc xor u(byte)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc and 65535
    }

    fun import(raw: ByteArray, name: String = "capture.bin", streamBytesPerSec: Double = 2400.0): BscImport {
        val records = StringBuilder("frame_no,time_epoch,udp_payload_hex,usb_crc_fail_total,usb_framing_error_total\n")
        var sync = 0
        var valid = 0
        var crcErrors = 0
        var framingErrors = 0
        var framed = 0
        var pos = 0
        while (pos + 8 <= raw.size) {
            if (u(raw[pos]) != 0x32 || u(raw[pos+1]) != 0x32 || u(raw[pos+2]) != 0x32 ||
                u(raw[pos+3]) != 0x01) { pos++; continue }
            sync++
            if (u(raw[pos+4]) !in TYPES || u(raw[pos+5]) != 0x40 ||
                u(raw[pos+6]) != 0x10 || u(raw[pos+7]) != 0x02) { pos += 4; continue }
            val start = pos
            pos += 8
            val inner = ArrayList<Byte>()
            var closed = false
            var wellFormed = true
            while (pos < raw.size && pos-start < 8192) {
                val b = u(raw[pos])
                if (b != 0x10) {
                    inner.add(raw[pos]); pos++; continue
                }
                if (pos + 1 >= raw.size) break
                val next = u(raw[pos + 1])
                when (next) {
                    0x03 -> { pos += 2; closed = true; break }
                    0x10 -> { inner.add(0x10); pos += 2 }
                    0x32 -> { pos += 2 } // transparent inserted DLE/SYN, excluded from CRC
                    else -> { wellFormed = false; inner.add(0x10); inner.add(next.toByte()); pos += 2 }
                }
            }
            if (!closed || pos + 1 >= raw.size) { framingErrors++; continue }
            val rx = u(raw[pos]) or (u(raw[pos+1]) shl 8)
            pos += 2
            framed++
            val scope = byteArrayOf(raw[start+4], raw[start+5], 0x10, 0x02) + inner.toByteArray() + byteArrayOf(0x03)
            val calculated = crc16Kermit(scope)
            if (calculated != rx) crcErrors++
            if (!wellFormed) framingErrors++
            if (calculated != rx || !wellFormed) continue
            valid++
            val payload = byteArrayOf(raw[start+4], raw[start+5]) + inner.toByteArray()
            val seconds = if (streamBytesPerSec > 0) start / streamBytesPerSec else 0.0
            records.append(framed).append(',')
                .append(String.format(Locale.US, "%.6f", seconds)).append(',')
                .append(payload.joinToString("") { "%02X".format(u(it)) }).append(',')
                .append(crcErrors).append(',').append(framingErrors).append('\n')
        }
        if (valid == 0) throw IllegalArgumentException("Το BIN δεν περιέχει έγκυρα BSC CRC frames")
        val base = AircatCsv.read(StringReader(records.toString()), name)
        val summary = base.summary.copy(crcFailuresDuringCsv = crcErrors.toLong(),
            framingFailuresDuringCsv = framingErrors.toLong())
        return BscImport(base.copy(summary = summary), BscStatus(raw.size, sync, valid,
            crcErrors, framingErrors, framed - valid))
    }
}
