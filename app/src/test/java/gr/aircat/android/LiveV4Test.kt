package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test

class LiveV4Test {
    @Test fun csvHasPythonColumnsAndCanReplay() {
        val packet=RadarPacket(1,1700000000.0,byteArrayOf(0x19,0x40,0xfd.toByte(),0,0,0),0x19,null,emptyList(),0,0)
        val row=LiveCsv.row(packet)
        assertEquals(16,LiveCsv.HEADER.split(",").size)
        assertEquals(16,row.split(",").size)
        assertTrue(row.contains("1940FD000000"))
        val data=AircatCsv.read(java.io.StringReader(LiveCsv.HEADER+"\n"+row+"\n"))
        assertEquals(1,data.packets.size)
    }
    @Test fun northTimingMatchesPythonThresholdsAfterWarmup() {
        val timing=LiveNorthTiming()
        for(i in 0..10)timing.add(i*4.0)
        timing.add(48.0)
        timing.add(52.4)
        assertEquals(1L,timing.snapshot().missed)
        assertEquals(1L,timing.snapshot().jitter)
    }
}
