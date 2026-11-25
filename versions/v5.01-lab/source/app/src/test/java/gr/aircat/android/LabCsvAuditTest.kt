package gr.aircat.android
import org.junit.Test
import org.junit.Assert.*

class LabCsvAuditTest{
    private fun audit(values:List<Int>):LabCsvAudit{
        val a=LabCsvAudit()
        values.forEachIndexed{i,v->
            if(v==-2)a.accept("GAP","","","","","")
            else{
                val p=if(v==-1)"1940FD000000" else "0C40FD%02X".format(v)
                a.accept("FRAME","0",(i*125000L).toString(),i.toString(),p,"LAB TCP v5")
            }
        }
        return a
    }
    @Test fun clean(){val a=audit(listOf(-1)+(0..31)+listOf(-1));assertEquals(1L,a.perfect);assertEquals(0L,a.observedMiss)}
    @Test fun sourceMissing(){val a=audit(listOf(-1)+(0..31).filter{it!=9}+listOf(-1));assertEquals(1L,a.observedMiss)}
    @Test fun transportGap(){val a=audit(listOf(-1,0,1,-2,20,21,-1));assertEquals(0L,a.observedMiss);assertEquals(1L,a.interrupted)}
    @Test fun noNorthBoundary(){val a=audit(listOf(-1)+(0..31)+listOf(0,1,-1));assertEquals(0L,a.observedMiss);assertEquals(1L,a.ambiguous)}
    @Test fun timeBackwards(){val a=LabCsvAudit();a.accept("FRAME","0","100","0","1940FD000000","LAB TCP v5");a.accept("FRAME","0","50","1","1940FD000000","LAB TCP v5");assertEquals(0L,a.observedMiss);assertEquals(1L,a.gaps)}
    @Test fun invalidRow(){val a=LabCsvAudit();a.accept("FRAME","","","","","");assertEquals(1L,a.invalidRows)}
    @Test fun incomplete(){assertEquals(0L,audit(listOf(-1,0,1)).observedMiss)}
    @Test fun legacyRefused(){try{LabCsvAudit.read("udp_payload_hex\n1940FD000000\n".reader());fail("legacy accepted")}catch(_:IllegalArgumentException){}}
}
