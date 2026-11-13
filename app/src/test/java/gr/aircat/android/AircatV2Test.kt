package gr.aircat.android

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class AircatV2Test {
    private fun capture(scans: Int, sectorsPerScan: Int): RadarCapture {
        val csv = buildString {
            appendLine("frame_no,time_epoch,udp_payload_hex")
            var n=1
            var time=0.0
            for (scan in 0 until scans) {
                appendLine("${n++},${time},1940FD000000")
                time += 0.1
                for (k in 0 until sectorsPerScan) {
                    val sec=k%32
                    appendLine("${n++},${time},0C40FD${"%02X".format(sec)}")
                    time += 0.1
                }
            }
            appendLine("${n},${time},1940FD000000")
        }
        return AircatCsv.read(StringReader(csv),"regression.csv")
    }
    @Test fun desktopBarsContain35CategoriesAndCorrectStacking() {
        val c = capture(2,32)
        val stats=AircatAnalysis.analyze(c)
        val bars=AircatDesktopSummary.sectorBars(c,stats)
        assertEquals(35,bars.labels.size)
        assertEquals("North",bars.labels.first())
        assertEquals("00",bars.labels[3])
        assertEquals("1F",bars.labels.last())
        assertEquals(2,bars.correct[0])
        assertEquals(2,bars.correct[3])
        assertEquals(0,bars.missing[3])
        assertEquals(0,bars.withoutNorth[3])
        assertTrue(AircatDesktopSummary.report(c,stats).contains("TRACKS PER SOURCE"))
    }
    @Test fun extraWrappedSectorsClassifiedWithoutPreviousNorth() {
        val c = capture(1,64)
        val stats = AircatAnalysis.analyze(c)
        val bars=AircatDesktopSummary.sectorBars(c,stats)
        assertEquals(0,stats.validCycles)
        assertEquals(1,stats.invalidCycles)
        assertEquals(1,bars.withoutNorth[3])
        assertEquals(1,bars.withoutNorth[34])
        assertEquals(0,bars.missing[0])
    }
    @Test fun labelDefaultsMatchV2Requirements() {
        val filter=RadarFilter()
        assertTrue(filter.showLabels)
        assertTrue(filter.showModeALabel)
        assertTrue(filter.showModeCLabel)
        assertFalse(filter.showSpeedLabel)
        assertFalse(filter.showVelocityVector)
        assertEquals(16,RadarReplayState().trailDepth)
        assertTrue(RadarFilter(showSpeedLabel=true).showSpeedLabel)
    }
}
