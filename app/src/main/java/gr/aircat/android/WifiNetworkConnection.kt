package gr.aircat.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** A generation owns one request; terminal paths always release it. */
class WifiNetworkConnection(
    private val activity: Activity,
    private val state: (String)->Unit,
    private val ready: (Network)->Unit,
    private val failed: (String)->Unit
) : AutoCloseable {
    companion object {
        const val PERMISSION_REQUEST=2303
        const val WIFI_PANEL_REQUEST=2305
    }
    private val cm=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi=activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler=Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback?=null
    private var current: Network?=null
    private var delivered: Network?=null
    private var generation=0
    private var pendingPassword: String?=null
    private var panelPassword: String?=null
    private var stage="IDLE"
    private var began=0L
    private var watchdog: Runnable?=null
    private var forceAssociation=false
    fun radioEnabled()=runCatching { wifi.isWifiEnabled }.getOrDefault(false)
    private fun hasEspAddress(p: LinkProperties?)=p?.linkAddresses?.any {
        val b=it.address.address
        b.size==4 && (b[0].toInt() and 255)==192 && (b[1].toInt() and 255)==168 &&
            (b[2].toInt() and 255)==4 && (b[3].toInt() and 255) in 2..254
    }==true
    private fun networks()=runCatching {
        cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
    }.getOrDefault(emptyList())
    private fun permission()=if(Build.VERSION.SDK_INT>=33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    private fun hasPermission()=activity.checkSelfPermission(permission())==PackageManager.PERMISSION_GRANTED
    fun diagnostics(): String {
        val nets=networks().joinToString("; ") { n ->
            val ips=runCatching { cm.getLinkProperties(n)?.linkAddresses?.filter { it.address.address.size==4 }
                ?.joinToString(",") { it.address.hostAddress ?: "?" } }.getOrNull().orEmpty()
            "net=$n IPv4=${ips.ifEmpty { "—" }}"
        }.ifEmpty { "κανένα" }
        val elapsed=if(began==0L) 0 else (SystemClock.elapsedRealtime()-began)/1000
        return "DEBUG: στάδιο=$stage · αίτημα=$generation · ${elapsed}s · Android API ${Build.VERSION.SDK_INT}\n" +
            "Wi-Fi=${if(radioEnabled()) "ON" else "OFF"} · άδεια=${if(hasPermission()) "OK" else "ΟΧΙ"} · δίκτυα: $nets"
    }
    private fun progress(next: String,message: String){stage=next;state(message)}
    private fun cancelWatchdog(){watchdog?.let(handler::removeCallbacks);watchdog=null}
    private fun timeout(token: Int,ms: Long,action: ()->Unit){
        cancelWatchdog()
        val task=Runnable { if(token==generation) action() }
        watchdog=task;handler.postDelayed(task,ms)
    }
    private fun fail(reason: String){
        val detail=diagnostics()
        release()
        failed("$reason\n$detail")
    }

    fun connect(password: String, forceNew: Boolean=false) {
        release()
        forceAssociation=forceNew
        began=SystemClock.elapsedRealtime()
        if(password.length !in 8..63){fail("Μη έγκυρο μήκος κωδικού Wi-Fi (8–63 χαρακτήρες).");return}
        if(!radioEnabled()){
            progress("WIFI_OFF","Το Wi-Fi είναι κλειστό. Ενεργοποίηση...")
            if(Build.VERSION.SDK_INT>=29) openWifiPanel(password)
            else {
                @Suppress("DEPRECATION")
                val accepted=runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
                if(accepted) awaitRadio(password,generation) else openWifiPanel(password)
            }
        } else connectEnabled(password)
    }
    private fun openWifiPanel(password: String){
        panelPassword=password
        progress("WIFI_PANEL","Ενεργοποίησε το Wi-Fi στο παράθυρο του Android και επέστρεψε.")
        try {
            val action=if(Build.VERSION.SDK_INT>=29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            activity.startActivityForResult(Intent(action),WIFI_PANEL_REQUEST)
        } catch(ex: Exception){
            try { activity.startActivityForResult(Intent(Settings.ACTION_WIFI_SETTINGS),WIFI_PANEL_REQUEST) }
            catch(other: Exception){fail("Δεν άνοιξαν οι ρυθμίσεις Wi-Fi: ${other.javaClass.simpleName}")}
        }
    }
    fun wifiPanelResult(){
        val password=panelPassword ?: return
        panelPassword=null
        awaitRadio(password,generation)
    }
    private fun awaitRadio(password: String,token: Int){
        progress("WIFI_ENABLE","Αναμονή ενεργοποίησης Wi-Fi...")
        val until=SystemClock.elapsedRealtime()+12000
        fun poll(){
            if(token!=generation)return
            if(radioEnabled()){connectEnabled(password);return}
            if(SystemClock.elapsedRealtime()>=until){fail("Το Wi-Fi παραμένει κλειστό. Ενεργοποίησέ το από το Android.");return}
            handler.postDelayed({poll()},350)
        }
        poll()
    }
    private fun connectEnabled(password: String){
        val existing=if(forceAssociation && Build.VERSION.SDK_INT>=29) null else
            networks().firstOrNull { hasEspAddress(cm.getLinkProperties(it)) }
        if(existing!=null){useCurrent(existing);return}
        if(Build.VERSION.SDK_INT<29){
            fail("Android 8/9: συνδέσου χειροκίνητα στο SYNC από τις ρυθμίσεις Wi-Fi και ξαναπάτησε Σύνδεση.");return
        }
        if(!hasPermission()){
            pendingPassword=password
            progress("PERMISSION","Απαιτείται άδεια Android για σύνδεση στο SYNC.")
            try { activity.requestPermissions(arrayOf(permission()),PERMISSION_REQUEST) }
            catch(ex: Exception){fail("Αδυναμία αιτήματος άδειας Wi-Fi: ${ex.javaClass.simpleName}")}
            return
        }
        if(Build.VERSION.SDK_INT in 29..32 &&
            !runCatching { (activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled }.getOrDefault(false)){
            fail("Η Τοποθεσία Android είναι κλειστή. Σε Android 10–12 απαιτείται για αυτόματη επιλογή Wi-Fi.");return
        }
        requestAutomatic(password)
    }
    fun permissionResult(granted: Boolean){
        val password=pendingPassword ?: return
        pendingPassword=null
        if(!granted)fail("Δεν δόθηκε η απαιτούμενη άδεια Wi-Fi. Έλεγξε τις άδειες εφαρμογής.")
        else if(radioEnabled())connectEnabled(password) else connect(password,forceAssociation)
    }
    private fun deliver(network: Network,properties: LinkProperties?){
        if(current!=network || delivered==network || !hasEspAddress(properties))return
        delivered=network
        cancelWatchdog()
        progress("HELLO_ACK","Wi-Fi και DHCP έτοιμα · αναμονή ACK UDP 5006...")
        ready(network)
    }
    private fun lostNetwork(network: Network,token: Int){
        if(token!=generation || current!=network)return
        fail("Χάθηκε το δίκτυο SYNC (Android onLost). Πιθανές αιτίες: Wi-Fi OFF, διακοπή ESP32 ή ασθενές σήμα.")
    }
    @android.annotation.TargetApi(29)
    private fun requestAutomatic(password: String){
        val token=generation
        progress("ASSOCIATION","Αναμονή σύνδεσης SYNC από το Android...")
        val cb=object: ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network: Network){
                if(token!=generation || current==network)return
                current=network
                progress("DHCP","Το Wi-Fi συνδέθηκε · αναμονή διεύθυνσης 192.168.4.x...")
                timeout(token,15000){fail("Σύνδεση Wi-Fi χωρίς διεύθυνση DHCP 192.168.4.x. Έλεγξε το AP ή δοκίμασε επανασύνδεση.")}
                deliver(network,cm.getLinkProperties(network))
            }
            override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties){
                if(token==generation && network==current){
                    if(delivered==network && !hasEspAddress(properties))fail("Χάθηκε η διεύθυνση DHCP του SYNC.")
                    else deliver(network,properties)
                }
            }
            override fun onLost(network: Network){lostNetwork(network,token)}
            override fun onUnavailable(){
                if(token==generation)fail("Android timeout πριν από το HELLO/PIN. Πιθανές αιτίες: SYNC εκτός εμβέλειας, λανθασμένος κωδικός, μη αποδοχή αιτήματος ή άλλη συνδεδεμένη συσκευή (ESP32 max 1).")
            }
        }
        callback=cb
        try {
            val spec=WifiNetworkSpecifier.Builder().setSsid("SYNC").setWpa2Passphrase(password).build()
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
            cm.requestNetwork(request,cb,handler,30000)
            timeout(token,35000){fail("Δεν παραλήφθηκε Android onAvailable. Πιθανή αποτυχία συσχέτισης AP/κωδικού ή εξάντληση θέσεων SYNC (max 1). PIN δεν ελέγχθηκε.")}
        } catch(ex: Exception){fail("Αδυναμία αιτήματος Android Wi-Fi: ${ex.javaClass.simpleName}. Έλεγξε άδειες/ρυθμίσεις.")}
    }
    private fun useCurrent(network: Network){
        val token=generation
        current=network
        progress("DHCP","Χρήση υπάρχοντος Wi-Fi 192.168.4.x...")
        val cb=object: ConnectivityManager.NetworkCallback(){
            override fun onLinkPropertiesChanged(n: Network,properties: LinkProperties){
                if(token==generation && n==current){
                    if(delivered==n && !hasEspAddress(properties))fail("Χάθηκε η διεύθυνση DHCP του SYNC.")
                    else deliver(n,properties)
                }
            }
            override fun onLost(n: Network){lostNetwork(n,token)}
        }
        callback=cb
        try {
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(request,cb,handler)
            timeout(token,15000){fail("Το υπάρχον Wi-Fi δεν έλαβε διεύθυνση DHCP 192.168.4.x.")}
            deliver(network,cm.getLinkProperties(network))
        } catch(ex: Exception){fail("Αδυναμία χρήσης υπάρχοντος Wi-Fi: ${ex.javaClass.simpleName}.")}
    }
    private fun release(){
        generation++
        cancelWatchdog()
        handler.removeCallbacksAndMessages(null)
        pendingPassword=null;panelPassword=null
        val old=callback;callback=null
        current=null;delivered=null;stage="IDLE"
        old?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
    override fun close(){release()}
}
