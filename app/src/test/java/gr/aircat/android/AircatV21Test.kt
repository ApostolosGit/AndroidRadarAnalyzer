package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test

class AircatV21Test {
    private fun pkt(n: Long, sector: Int?, tracks: List<RadarTrack>,
                    kind: Int = if (sector == null) 0x19 else 0x0C): RadarPacket =
        RadarPacket(n, n.toDouble(), byteArrayOf(), kind, sector, tracks, null, null)

    private fun track(number: Int, x: Int = 100) =
        RadarTrack(number, x, 50, false, "1200", "10000ft")

    @Test fun northPreservesTargetsUntilSectorIsRefreshed() {
        val state = RadarReplayState()
        state.apply(pkt(1,0,listOf(track(3))))
        state.apply(pkt(2,1,listOf(track(4))))
        assertEquals(2,state.allTracks().size)
        state.apply(pkt(3,null,emptyList()))
        assertEquals(2,state.allTracks().size)
        assertEquals(1,state.cycle)
        state.apply(pkt(4,0,emptyList()))
        assertEquals(listOf(4),state.allTracks().map { it.number })
        state.apply(pkt(5,1,emptyList()))
        assertTrue(state.allTracks().isEmpty())
    }

    @Test fun movingTrackIsNotDuplicatedAcrossSectors() {
        val state = RadarReplayState()
        state.apply(pkt(1,0,listOf(track(19, 100))))
        state.apply(pkt(2,null,emptyList()))
        state.apply(pkt(3,1,listOf(track(19,200))))
        assertEquals(1,state.allTracks().size)
        assertEquals(200,state.allTracks().first().xRaw)
        assertTrue(state.tracksBySector[0].isNullOrEmpty())
        assertEquals(2,state.trackTrail[19]?.size)
    }

    @Test fun cancellationRemovesAircraftImmediately() {
        val state = RadarReplayState()
        state.apply(pkt(1,0,listOf(track(42))))
        state.apply(pkt(2,1,listOf(RadarTrack(42,0,0,false,null,cancellation=true))))
        assertTrue(state.allTracks().isEmpty())
    }

    @Test fun defaultLabelsAndTrailHistory() {
        val filter = RadarFilter()
        assertTrue(filter.showLabels)
        assertTrue(filter.showModeALabel)
        assertTrue(filter.showModeCLabel)
        assertFalse(filter.showTrackNumberLabel)
        assertEquals(16,RadarReplayState().trailDepth)
    }
}
