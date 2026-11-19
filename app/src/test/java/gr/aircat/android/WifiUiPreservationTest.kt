package gr.aircat.android

import android.os.Looper
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiUiPreservationTest {
    @Before fun clearRecordingHistory() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("aircat_wifi_recordings",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun field(activity: MainActivity,name: String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun call(activity: MainActivity,name: String)=MainActivity::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(activity)
    private fun chooseFile(activity: MainActivity)=MainActivity::class.java.getDeclaredMethod("chooseFile",Boolean::class.javaPrimitiveType)
        .apply{isAccessible=true}.invoke(activity,true)
    private fun completePrompt(activity: MainActivity,uri: Uri)=MainActivity::class.java.getDeclaredMethod("offerCompletedWifiRecording",Uri::class.java)
        .apply{isAccessible=true}.invoke(activity,uri)
    private fun waitUntil(test: ()->Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!test() && System.nanoTime()<until){Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle()}
        assertTrue("Timed out",test())
    }
    private fun savedCsv()=File.createTempFile("wifi-recording-",".csv").apply {
        writeText("frame_no,time_epoch,udp_payload_hex\n1,1000,1940FD000000\n2,1004,0C40FD00\n3,1008,0C40FD01\n4,1012,0C40FD02\n")
    }
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
    @Test fun stopAndCloseWifiNeverResumePreviouslyPlayingReplay() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        try {
            prepare(activity);call(activity,"start")
            assertTrue(field(activity,"playing").getBoolean(activity))
            call(activity,"showWifiWindow")
            assertFalse(field(activity,"playing").getBoolean(activity))
            val dialog=field(activity,"wifiDialog").get(activity) as WifiLiveDialog
            WifiLiveDialog::class.java.getDeclaredMethod("disconnect").apply{isAccessible=true}.invoke(dialog)
            assertFalse(field(activity,"playing").getBoolean(activity))
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle() // Android dispatches onDismiss asynchronously.
            assertFalse(field(activity,"playing").getBoolean(activity))
        } finally{controller.pause().stop().destroy()}
    }

    @Test fun finishedCsvOffersChoiceWithoutStartingReplayAndLaterKeepsLive() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val file=File.createTempFile("wifi-finished-",".csv")
        val session=AircatWifiSession({})
        try {
            val capture=prepare(activity);call(activity,"start");call(activity,"showWifiWindow")
            val live=field(activity,"wifiDialog").get(activity) as WifiLiveDialog
            AircatWifiSession::class.java.getDeclaredField("started").apply{isAccessible=true}.setBoolean(session,true)
            MainActivity::class.java.getDeclaredMethod("startWifiRecording",Uri::class.java,AircatWifiSession::class.java)
                .apply{isAccessible=true}.invoke(activity,Uri.fromFile(file),session)
            val recorder=session.recorder!!
            recorder.offer(WifiRecordRow(capture.packets.first(),WifiCounters()))
            recorder.stop();waitUntil{recorder.finished}
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Uri.fromFile(file),WifiRecordingHistory(activity).latest())
            assertEquals(1,AircatCsv.read(StringReader(file.readText()),file.name).packets.size)
            assertSame(capture,field(activity,"capture").get(activity))
            assertFalse(field(activity,"playing").getBoolean(activity))
            val prompt=ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Replay",prompt.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals("Ανάλυση",prompt.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(live.isShowing)
            assertFalse(field(activity,"playing").getBoolean(activity))
        } finally{session.close();controller.pause().stop().destroy();file.delete()}
    }

    @Test fun explicitReplayClosesLiveAndLoadsNewRecording()=verifyCompletedChoice(true)
    @Test fun explicitAnalysisClosesLiveAndLoadsRecordingWithoutPlayback()=verifyCompletedChoice(false)
    private fun verifyCompletedChoice(playback: Boolean) {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val file=savedCsv()
        try {
            val previous=prepare(activity);call(activity,"showWifiWindow")
            val live=field(activity,"wifiDialog").get(activity) as WifiLiveDialog
            completePrompt(activity,Uri.fromFile(file))
            assertSame(previous,field(activity,"capture").get(activity))
            assertFalse(field(activity,"playing").getBoolean(activity))
            ShadowAlertDialog.getLatestAlertDialog().getButton(
                if(playback)AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEUTRAL).performClick()
            waitUntil{field(activity,"capture").get(activity)!==previous}
            assertFalse(live.isShowing)
            assertEquals(file.name,(field(activity,"capture").get(activity) as RadarCapture).name)
            assertEquals(playback,field(activity,"playing").getBoolean(activity))
            if(!playback)assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally{controller.pause().stop().destroy();file.delete()}
    }

    @Test fun nextOpenSuggestsCompletedRecordingAndOtherFileUsesNormalPicker() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val uri=Uri.parse("content://documents/capture.csv")
        try {
            val previous=prepare(activity)
            WifiRecordingHistory(activity).completed(uri,10,null)
            chooseFile(activity)
            assertSame(previous,field(activity,"capture").get(activity))
            assertFalse(field(activity,"playing").getBoolean(activity))
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            chooseFile(activity)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val intent=shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
            assertEquals(uri,intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        } finally{controller.pause().stop().destroy()}
    }

    @Test fun pendingRecordingCannotBeOfferedAsReadyForReplay() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val gate=CountDownLatch(1)
        val recorder=WifiCsvRecorder{gate.await(2,TimeUnit.SECONDS);java.io.ByteArrayOutputStream()}
        try {
            prepare(activity)
            field(activity,"wifiRecording").set(activity,recorder)
            chooseFile(activity)
            assertEquals("Άλλο αρχείο",ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertFalse(field(activity,"playing").getBoolean(activity))
        } finally{recorder.stop();gate.countDown();controller.pause().stop().destroy()}
    }

    @Test fun historySurvivesRecreationAndIgnoresEmptyOrFailedRecordings() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        val history=WifiRecordingHistory(context)
        assertNull(history.latest())
        val uri=Uri.parse("content://documents/complete.csv")
        history.completed(uri,10,null)
        history.completed(Uri.parse("content://documents/empty.csv"),0,null)
        history.completed(Uri.parse("content://documents/failed.csv"),5,"write failed")
        assertEquals(uri,WifiRecordingHistory(context).latest())
    }
}
