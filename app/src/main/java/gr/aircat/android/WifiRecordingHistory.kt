package gr.aircat.android

import android.content.Context
import android.net.Uri

/** Only a fully closed, nonempty, successful recording becomes the replay suggestion. */
class WifiRecordingHistory(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("aircat_wifi_recordings",Context.MODE_PRIVATE)
    fun latest(): Uri?=prefs.getString("last_completed_csv",null)?.let{Uri.parse(it)}
    fun completed(uri: Uri,rows: Long,error: String?) {
        if(rows>0 && error==null)prefs.edit().putString("last_completed_csv",uri.toString()).apply()
    }
}
