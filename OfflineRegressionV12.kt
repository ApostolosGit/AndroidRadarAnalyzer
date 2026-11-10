import gr.aircat.android.*
import java.io.File
fun main(args: Array<String>) {
    check(!RadarFilter().showTrackNumber)
    check(RadarFilter().showModeA && RadarFilter().showModeC)
    check(RadarReplayState().trailDepth == 10)
    check(PpiScale.maxRange(1f) == 250.0)
    check(PpiScale.maxRange(2f) == 125.0)
    check(PpiScale.rings(250.0).contains(50.0))
    check(PpiScale.rings(250.0).contains(250.0))
    val state=RadarReplayState()
    for(i in 1..15) {
        val track=RadarTrack(17,i*100,i*200,false,"1200")
        state.apply(RadarPacket(i.toLong(),i/10.0,byteArrayOf(0x0C,0x40,0xFD.toByte(),0),0x0C,0,listOf(track),null,null))
    }
    check(state.trackTrail[17]?.size == 10)
    check(state.trackTrail[17]?.last()?.xRaw == 1500)
    check(state.trackTrail[17]?.first()?.xRaw == 600)
    println("Core defaults, 10-point trails and zoom/range scaling PASS")
    args.forEach { name ->
        val data = File(name).reader().use { AircatCsv.read(it,File(name).name) }
        val diag = AircatAnalysis.analyze(data)
        val errs = AircatSummaries.errorRows(diag)
        val alerts = AircatSummaries.alertRows(diag)
        check(errs.size==13)
        check(errs.any { it.name.contains("διπλομέτρηση") })
        check(errs.first().name=="Scan missed")
        check(alerts.all { it.psr>=0 && it.mssr>=0 && it.other>=0 })
        println("${data.name}: ${data.summary.packets} frames, ${diag.completedCycles} cycles, ${diag.missingTotal} missing, ${alerts.size} alert categories; PASS")
    }
}
