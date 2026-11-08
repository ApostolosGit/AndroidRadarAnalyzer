import gr.aircat.android.*
import java.io.File
import java.io.StringReader

fun main(args: Array<String>) {
    check(args.size == 2)
    var total = 0
    for (path in args) {
        val result = File(path).reader(Charsets.UTF_8).use { AircatCsv.read(it, File(path).name) }
        println("${result.name}: ${result.summary}")
        println("first targets: " + result.packets.firstOrNull { it.tracks.isNotEmpty() }?.tracks?.take(3))
        check(result.summary.packets > 100)
        check(result.summary.north > 0)
        check(result.summary.sectors > 0)
        check(result.summary.tracksDecoded > 0)
        total += result.summary.packets
    }
    check(total == 8543) { "Expected 8543 frames, got $total" }
    check(AircatCsv.columns("a,\"b,c\",\"d\"\"e\"") == listOf("a", "b,c", "d\"e"))
    check(AircatCsv.hexBytes("19 40 FD 00 00 00")?.size == 6)
    val fake = "frame_no,time_epoch,udp_payload_hex\n1,1.0,1940FD000000\n2,2.0,0C40FD00\n3,3.0,0C40FD00\n4,4.0,1940FD000000\n"
    val damaged = AircatCsv.read(StringReader(fake))
    check(damaged.summary.missingSectorIds == 31)
    check(damaged.summary.duplicateSectors == 1)
    check(damaged.summary.completeRotations == 1)
    println("PASS: 8543 verified CSV entries, basic tracks, North boundaries and duplicates")
}
