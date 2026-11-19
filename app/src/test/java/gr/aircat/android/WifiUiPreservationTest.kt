package gr.aircat.android

import android.os.Looper
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiUiPreservationTest {
    private fun field(activity: MainActivity,name: String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun call(activity: MainActivity,name: String)=MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(activity)
    private fun prepare(activity: MainActivity): RadarCapture {
        val capture=AircatCsv.read(StringReader("frame_no,time_epoch,udp_payload_hex\n1,1000,1940FD000000\n2,1004,0C40FD00\n3,1008,0C40FD01\n4,1012,0C40FD02\n"),"saved.csv")
        field(activity,"capture").set(activity,capture)
        field(activity,"diagnostics").set(activity,AircatAnalysis.analyze(capture))
        MainActivity::class.java.getDeclaredMethod("jumpTo",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .apply{isAccessible=true}.invoke(activity,0,true)
        return capture
    }
    @Test fun openingAndClosingWifiPreservesLoadedFileCursorAndRenderState() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        try {
            val capture=prepare(activity)
            val before=field(activity,"record").getInt(activity)
            val replay=field(activity,"replay").get(activity) as RadarReplayState
            val cycle=replay.cycle
            call(activity,"showWifiWindow")
            val dialog=field(activity,"wifiDialog").get(activity) as WifiLiveDialog
            assertTrue(dialog.isShowing)
            assertSame(capture,field(activity,"capture").get(activity))
            assertEquals(before,field(activity,"record").getInt(activity))
            assertEquals(cycle,replay.cycle)
            dialog.dismiss();shadowOf(Looper.getMainLooper()).idle()
            assertSame(capture,field(activity,"capture").get(activity))
            assertEquals(before,field(activity,"record").getInt(activity))
            assertFalse(field(activity,"playing").getBoolean(activity))
        } finally{controller.pause().stop().destroy()}
    }
    @Test fun returningFromWifiResumesPreviouslyPlayingReplay() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        try {
            prepare(activity);call(activity,"start")
            assertTrue(field(activity,"playing").getBoolean(activity))
            call(activity,"showWifiWindow")
            assertFalse(field(activity,"playing").getBoolean(activity))
            (field(activity,"wifiDialog").get(activity) as WifiLiveDialog).dismiss()
            shadowOf(Looper.getMainLooper()).idle() // Android dispatches onDismiss asynchronously.
            assertTrue(field(activity,"playing").getBoolean(activity))
        } finally{controller.pause().stop().destroy()}
    }
}
