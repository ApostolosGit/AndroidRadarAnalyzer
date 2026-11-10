package gr.aircat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class AircatCoreTest {
    @Test fun acceptsPythonCsvEscaping() {
        assertEquals(listOf("a", "b,c", "d\"e"), AircatCsv.columns("a,\"b,c\",\"d\"\"e\""))
    }

    @Test fun detectsMissingIdsWithoutDoubleCountingDuplicatedFrame() {
        val csv = "frame_no,time_epoch,udp_payload_hex\n1,1,1940FD000000\n2,2,0C40FD00\n3,3,0C40FD00\n4,4,1940FD000000\n"
        val out = AircatCsv.read(StringReader(csv))
        assertEquals(1, out.summary.completeRotations)
        assertEquals(31, out.summary.missingSectorIds)
        assertEquals(1, out.summary.duplicateSectors)
        assertNull(out.summary.crcFailuresDuringCsv)
    }

    @Test fun ignoresPartialFirstAndLastCycles() {
        val csv = "frame_no,time_epoch,udp_payload_hex\n1,1,0C40FD1F\n2,2,1940FD000000\n3,3,0C40FD00\n"
        val out = AircatCsv.read(StringReader(csv))
        assertEquals(0, out.summary.completeRotations)
        assertEquals(0, out.summary.missingSectorIds)
    }
}
