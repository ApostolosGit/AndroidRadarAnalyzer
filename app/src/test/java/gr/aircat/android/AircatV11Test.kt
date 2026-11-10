package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test

/** Offline state restoration / filters; no Android emulator required. */
class AircatV11Test {
    private fun north(frame: Long, time: Double) =
        RadarPacket(frame, time, byteArrayOf(0x19, 0x40, 0xFD.toByte(), 0, 0, 0), 0x19, null, emptyList(), null, null)
    private fun sector(frame: Long, time: Double, id: Int, tracks: List<RadarTrack>) =
        RadarPacket(frame, time, byteArrayOf(0x0C, 0x40, 0xFD.toByte(), id.toByte()), 0x0C, id, tracks, null, null)

    @Test fun targetsPersistAcrossNorthUntilExplicitClear() {
        val state = RadarReplayState()
        val psr = RadarTrack(7, 320, 500, primary = true, modeA = null)
        state.apply(north(1,0.0))
        state.apply(sector(2,0.1,0,listOf(psr)))
        state.apply(north(3,4.0))
        assertEquals(listOf(psr), state.allTracks())
        assertEquals(2,state.cycle)
        state.clearVisibleTargets()
        assertTrue(state.allTracks().isEmpty())
    }

    @Test fun checkpointsRebuildForwardAndBackwards() {
        val psr = RadarTrack(7,320,500,true,null)
        val ssr = RadarTrack(11,120,450,false,"1200",modeC="15500ft",speedKt=215)
        val source = listOf(
            north(1,0.0),sector(2,0.1,0,listOf(psr)),north(3,4.0),
            sector(4,4.1,1,listOf(ssr)),sector(5,4.2,0,listOf(psr.copy(xRaw=400))))
        val snapshots = ReplayCheckpoints(source, stride=2)
        val state = RadarReplayState()
        snapshots.restore(state,4)
        assertEquals(400,state.allTracks().first { it.number==7 }.xRaw)
        assertEquals(11,state.allTracks().first { it.number==11 }.number)
        snapshots.restore(state,1)
        assertEquals(1,state.allTracks().size)
        assertEquals(320,state.allTracks().single().xRaw)
        snapshots.restore(state,3)
        assertEquals(2,state.allTracks().size)
        assertEquals(3,snapshots.indexAtOrBefore(4.15))
        assertEquals(0,snapshots.indexAtOrBefore(-100.0))
        assertEquals(4,snapshots.indexAtOrBefore(999.0))
    }

    @Test fun numericFiltersAndTargetCategories() {
        val psr = RadarTrack(1,0,0,true,null)
        val ssr = RadarTrack(2,0,0,false,"1234",modeC="15000ft",speedKt=245)
        val combined = RadarTrack(3,0,0,true,"5555",modeC="-500ft",speedKt=80,combined=true)
        assertFalse(RadarFilter(minAltitudeFt=10000).allows(psr))
        assertTrue(RadarFilter(minAltitudeFt=10000,maxAltitudeFt=16000,minSpeedKt=200).allows(ssr))
        assertFalse(RadarFilter(minAltitudeFt=16000).allows(ssr))
        assertFalse(RadarFilter(maxSpeedKt=200).allows(ssr))
        assertFalse(RadarFilter(showCombined=false).allows(combined))
        assertTrue(RadarFilter(showPSR=false).allows(ssr))
        assertFalse(RadarFilter(showPSR=false).allows(psr))
        assertEquals(-500,RadarFilter.altitudeFeet("-500ft"))
        assertNull(RadarFilter.altitudeFeet("0xF91"))
        assertTrue(RadarFilter().showModeA && RadarFilter().showModeC)
    }
}
