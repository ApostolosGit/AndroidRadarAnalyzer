package gr.aircat.android

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class AircatV1Test {
    @Test fun kermitReferenceVector() {
        assertEquals(0x2189, AircatBsc.crc16Kermit("123456789".toByteArray()))
    }
    @Test fun missingSectorNotDoubleCounted() {
        val csv = buildString {
            appendLine("frame_no,time_epoch,udp_payload_hex")
            appendLine("1,0.000,1940FD000000")
            for (i in 0..31) if (i != 5) appendLine("${i + 2},${i / 8.0},0C40FD${"%02X".format(i)}")
            appendLine("34,4.000,1940FD000000")
        }
        val capture = AircatCsv.read(StringReader(csv))
        val d = AircatAnalysis.analyze(capture)
        assertEquals(1,d.missingTotal)
        assertEquals(1,d.invalidCycles)
        assertEquals(1,d.distinctFaults)
        assertEquals(0,d.cycles.first().orderFaults)
    }
    @Test fun completeCycleHasZeroMissing() {
        val csv = buildString {
            appendLine("frame_no,time_epoch,udp_payload_hex")
            appendLine("1,0.000,1940FD000000")
            for (i in 0..31) appendLine("${i + 2},${i / 8.0},0C40FD${"%02X".format(i)}")
            appendLine("34,4.000,1940FD000000")
        }
        val d = AircatAnalysis.analyze(AircatCsv.read(StringReader(csv)))
        assertEquals(1,d.validCycles)
        assertEquals(0,d.distinctFaults)
    }
}
