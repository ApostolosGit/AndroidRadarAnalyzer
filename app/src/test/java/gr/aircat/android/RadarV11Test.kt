package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the additional Android radar/Replay controls. */
class RadarV11Test {
    private fun track(
        id: Int,
        altitude: String? = null,
        speed: Int? = null,
        primary: Boolean = false
    ) = RadarTrack(
        number=id, xRaw=32*10, yRaw=0, primary=primary,
        modeA="7000", modeC=altitude, speedKt=speed
    )

    @Test fun defaultLabelsAndTrailsAreEnabled() {
        val filter = RadarFilter()
        assertTrue(filter.showLabels)
        assertTrue(filter.showTrails)
        val replay = RadarReplayState()
        assertEquals(64, replay.trailDepth) // available history; visible default is 16
    }

    @Test fun altitudeAndSpeedFiltersHonorMissingValues() {
        val f = RadarFilter(minAltitudeFt=5000, maxAltitudeFt=25000, minSpeedKt=100, maxSpeedKt=500)
        assertTrue(f.allows(track(1,"12000ft",240)))
        assertFalse(f.allows(track(2,"2000ft",240)))
        assertFalse(f.allows(track(3,"30000ft",240)))
        assertFalse(f.allows(track(4,"12000ft",90)))
        assertFalse(f.allows(track(5,"12000ft",650)))
        assertFalse(f.allows(track(6,null,240)))
        assertFalse(f.allows(track(7,"12000ft",null)))
        assertTrue(RadarFilter(minAltitudeFt=-2500,maxAltitudeFt=0).allows(track(8,"-1200ft",0)))
    }

    @Test fun optionalRangeFilterUsesRealAircraftDistance() {
        assertTrue(RadarFilter(maxRangeNm=20.0).allows(track(1)))
        assertFalse(RadarFilter(maxRangeNm=5.0).allows(track(1)))
    }

    @Test fun reverseTimeMapsToRecordedFramesOnly() {
        val frames = listOf(1.0,2.0,2.0,3.0,10.0).mapIndexed { i,epoch ->
            RadarPacket(i.toLong(),epoch,byteArrayOf(),0, null, emptyList(),null,null)
        }
        assertEquals(0,ReplayTimeline.frameAt(frames,0.0))
        assertEquals(2,ReplayTimeline.frameAt(frames,2.0))
        assertEquals(3,ReplayTimeline.frameAt(frames,3.5))
        assertEquals(4,ReplayTimeline.frameAt(frames,99.0))
    }

    @Test fun seekSnapshotRestoresTrackAndTrailHistory() {
        val state = RadarReplayState()
        fun frame(n: Int) = RadarPacket(n.toLong(),n.toDouble(),byteArrayOf(),
            0x0C, n % 32, listOf(track(19,"12000ft",250)),null,null)
        for (i in 0..130) state.apply(frame(i))
        val saved=state.snapshot()
        assertEquals(64,state.trackTrail[19]?.size)
        state.clear()
        assertTrue(state.allTracks().isEmpty())
        state.restore(saved)
        assertEquals(131,state.packetsShown)
        assertEquals(64,state.trackTrail[19]?.size)
        assertEquals(130 % 32,state.sector)
    }
}
