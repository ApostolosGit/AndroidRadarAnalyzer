package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test

class AircatV12Test {
    private fun north(i: Long, time: Double) = RadarPacket(
        i,time,byteArrayOf(0x19,0x40,0xFD.toByte(),0,0,0),0x19,null,emptyList(),null,null)
    private fun sector(i: Long, time: Double, sector: Int, tracks: List<RadarTrack>) = RadarPacket(
        i,time,byteArrayOf(0x0C,0x40,0xFD.toByte(),sector.toByte()),0x0C,sector,tracks,null,null)

    @Test fun aviationPpiShowsPhysicalRangeRings() {
        assertEquals(250.0, PpiScale.maxRange(1f),1e-7)
        assertEquals(125.0, PpiScale.maxRange(2f),1e-7)
        assertEquals("250",PpiScale.formatNm(250.0))
        assertEquals(listOf(50.0,100.0,150.0,200.0,250.0),PpiScale.rings(250.0))
        assertTrue(PpiScale.rings(125.0).all { it <= 125.0 })
    }
    @Test fun historicalTrailsKeepTenDifferentPositionsByDefault() {
        val state=RadarReplayState()
        assertEquals(10,state.trailDepth)
        for(i in 1..15) state.apply(sector(i.toLong(),i/10.0,0,
            listOf(RadarTrack(55,i*32,64,primary=false,modeA="1200"))))
        assertEquals(10,state.trackTrail[55]!!.size)
        assertEquals(6*32,state.trackTrail[55]!!.first().xRaw)
        state.apply(north(16,4.0))
        assertEquals(10,state.trackTrail[55]!!.size)
        assertTrue(state.allTracks().isNotEmpty())
        val checkpoints=ReplayCheckpoints(listOf(north(1,0.0),
            sector(2,.1,0,listOf(RadarTrack(2,32,64,false,"1200"))),
            sector(3,.2,0,listOf(RadarTrack(2,40,68,false,"1200")))),stride=2)
        val restored=RadarReplayState()
        checkpoints.restore(restored,2)
        assertEquals(2,restored.trackTrail[2]!!.size)
    }
    @Test fun errorSummaryRowsNonAdditiveAndAlertSourceSplit() {
        val s=RadarTrack(2,32,64,false,"1200",quality=90,maneuver=true,spi=true)
        val p=RadarTrack(3,42,74,true,null,quality=1)
        val capture=AircatCsv.read(java.io.StringReader("time_epoch,frame_no,udp_payload_hex\n"+
          "0.0,1,1940FD000000\n"),"example.csv")
        val diag=AircatAnalysis.analyze(RadarCapture(
            listOf(north(1,0.0), sector(2,0.1,0,listOf(s,p)),
                sector(3,0.2,1,listOf(RadarTrack(2,0,0,false,null,cancellation=true))),
                north(4,4.0)),capture.summary,"sample"))
        val rows=AircatSummaries.errorRows(diag)
        assertEquals(13,rows.size)
        assertEquals("30",rows.first { it.name=="Sector missing συνολικά" }.value)
        val alerts=AircatSummaries.alertRows(diag)
        assertEquals(1,alerts.first { it.name=="Low quality" }.psr)
        assertEquals(1,alerts.first { it.name=="Low quality" }.mssr)
        assertEquals(1,alerts.first { it.name=="Maneuver" }.mssr)
        assertEquals(1,alerts.first { it.name=="SPI" }.mssr)
        assertEquals(1,alerts.first { it.name=="Track cancellation" }.mssr)
        assertFalse(RadarFilter().showTrackNumber)
        assertTrue(RadarFilter().showModeA)
        assertTrue(RadarFilter().showModeC)
    }
}
