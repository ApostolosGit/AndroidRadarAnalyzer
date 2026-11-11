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
    @Test fun rangeRingsCoverPannedViewportAndDefault20Nm() {
        assertEquals(5, RadarRangeGrid.spacingNm(20.0))
        assertEquals(listOf(5, 10, 15, 20, 25), RadarRangeGrid.rings(20.0, 20.0))
        val pannedRings = RadarRangeGrid.rings(20.0, 130.0)
        assertTrue(pannedRings.contains(130))
        assertTrue(pannedRings.last() >= 130)
    }
    @Test fun seekUsesTrueTimeRatherThanFrameCount() {
        val c = AircatCsv.read(StringReader(
            "frame_no,time_epoch,udp_payload_hex\\n1,10.0,1940FD000000\\n2,10.1,0C40FD00\\n3,20.0,0C40FD01\\n4,40.0,1940FD000000\\n"
                .replace("\\n", "\n")
        ))
        val p = c.packets
        assertEquals(1, ReplayTimeline.indexAtOrBefore(p, 19.0))
        assertEquals(2, ReplayTimeline.indexAtOrBefore(p, 21.0))
        val atHalf = ReplayTimeline.epoch(5000, 10.0, 40.0)
        assertEquals(25.0, atHalf, 1e-9)
        assertEquals(5000, ReplayTimeline.position(atHalf, 10.0, 40.0))
    }
    @Test fun altitudeAndSpeedFiltersIncludeOnlyMatchingTracks() {
        val valid = RadarTrack(12, 100, 200, false, "7000", modeC = "12500ft", speedKt = 240)
        val invalid = RadarTrack(13, 100, 200, false, "7000", modeC = "6500ft", speedKt = 120)
        val filter = RadarFilter(minAltitudeFeet = 10000, maxAltitudeFeet = 18000,
            minSpeedKt = 200, maxSpeedKt = 350)
        assertTrue(filter.allows(valid))
        assertFalse(filter.allows(invalid))
        assertFalse(filter.allows(RadarTrack(14, 100, 200, true, null)))
        assertTrue(RadarFilter().allows(invalid))
        assertEquals(16, RadarReplayState().trailDepth)
        assertTrue(RadarFilter().showLabels)
    }

}
