package gr.aircat.android

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class AircatV22Test {
    @Test fun reportStartsWithExactlyOneTitleAndFilename() {
        val csv = buildString {
            appendLine("frame_no,time_epoch,udp_payload_hex")
            appendLine("1,0.0,1940FD000000")
            for (i in 0..31) appendLine("${i + 2},${i / 8.0},0C40FD${"%02X".format(i)}")
            appendLine("34,4.0,1940FD000000")
        }
        val data = AircatCsv.read(StringReader(csv), "flight.csv")
        val stats = AircatAnalysis.analyze(data)
        val report = AircatDesktopSummary.report(data,stats)
        val lines = report.lines()
        assertTrue(lines[0].contains("v2.2"))
        assertEquals("Αρχείο: flight.csv", lines[1])
        assertFalse(lines[2].contains("Desktop v9.16-oriented"))
        for (line in lines) {
            if (line.isNotEmpty() && line.all { it == '-' || it == '=' ||
                it == '─' || it == '═' }) {
                assertTrue("Overlong report rule (${line.length} chars)",line.length <= 50)
            }
        }
        assertTrue(AircatDesktopSummary.errors(data,stats).contains("MISSING SECTORS"))
        assertTrue(AircatDesktopSummary.alerts(data,stats).contains("MODE A / MODE C VALIDATION"))
    }

    @Test fun defaultRangeAndScanBehaviorRemain() {
        val state = RadarReplayState()
        assertEquals(16, state.trailDepth)
        val source = RadarFilter()
        assertTrue(source.showModeALabel)
        assertTrue(source.showModeCLabel)
        assertFalse(source.showTrackNumberLabel)
        val first = RadarPacket(1, 0.0, byteArrayOf(), 0x0C, 0,
            listOf(RadarTrack(10,100,20,false,"4321")), null, null)
        val north = RadarPacket(2, 1.0, byteArrayOf(), 0x19, null, emptyList(), null, null)
        state.apply(first)
        state.apply(north)
        assertEquals(1,state.allTracks().size)
    }
}
