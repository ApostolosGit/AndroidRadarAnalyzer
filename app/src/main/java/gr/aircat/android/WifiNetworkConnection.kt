package gr.aircat.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Local-only WiFi request. Bind each UDP socket, never the whole process. */
class WifiNetworkConnection(
    private val activity: Activity,
    private val state: (String)->Unit,
    private val ready: (Network)->Unit,
    private val lost: ()->Unit
) : AutoCloseable {
    companion object { const val PERMISSION_REQUEST=2303 }
    private val cm=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler=Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback?=null
    private var current: Network?=null
    private var delivered: Network?=null
    private var generation=0
    private var pendingPassword: String?=null
    private fun hasEspAddress(properties: LinkProperties?) = properties?.linkAddresses?.any {
        val bytes=it.address.address
        bytes.size==4 && (bytes[0].toInt() and 255)==192 &&
            (bytes[1].toInt() and 255)==168 && (bytes[2].toInt() and 255)==4
    }==true

    fun automatic(password: String) {
        if(Build.VERSION.SDK_INT<29) {
            state("Android 8/9: συνδέσου στο SYNC από τις ρυθμίσεις Wi-Fi και επίλεξε «Τρέχον Wi-Fi».")
            return
        }
        require(password.length in 8..63){"Ο κωδικός Wi-Fi χρειάζεται 8–63 χαρακτήρες"}
        val permission=if(Build.VERSION.SDK_INT>=33)Manifest.permission.NEARBY_WIFI_DEVICES
                       else Manifest.permission.ACCESS_FINE_LOCATION
        if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) {
            pendingPassword=password
            state("Απαιτείται άδεια Android για σύνδεση στη συσκευή SYNC.")
            activity.requestPermissions(arrayOf(permission),PERMISSION_REQUEST)
            return
        }
        requestAutomatic(password)
    }

    fun permissionResult(granted: Boolean) {
        val password=pendingPassword;pendingPassword=null
        if(!granted) {
            state("Η άδεια δεν δόθηκε. Μπορείς να συνδεθείς από τις ρυθμίσεις και να χρησιμοποιήσεις το τρέχον Wi-Fi.")
        } else if(password!=null)automatic(password)
    }

    @android.annotation.TargetApi(29)
    private fun requestAutomatic(password: String) {
        release();val token=generation
        state("Αναμονή επιλογής/σύνδεσης SYNC από το Android...")
        val cb=object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if(token!=generation)return
                if(current==network)return
                current=network;state("Wi-Fi διαθέσιμο · αναμονή DHCP 192.168.4.x...")
            }
            override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties) {
                if(token!=generation || current!=network || delivered==network || !hasEspAddress(properties))return
                delivered=network
                state("Wi-Fi και DHCP έτοιμα · επαλήθευση ESP32 με HELLO/ACK...")
                ready(network)
            }
            override fun onLost(network: Network) {
                if(token!=generation || current!=network)return
                current=null;delivered=null;lost();state("Χάθηκε το SYNC · αναμονή επανασύνδεσης...")
            }
            override fun onUnavailable() {
                if(token==generation)state("Δεν ολοκληρώθηκε η σύνδεση SYNC. Έλεγξε AP, κωδικό και άδειες Android. Σε Android 10–12 έλεγξε και την ενεργοποίηση Τοποθεσίας.")
            }
        }
        callback=cb
        try {
            val spec=WifiNetworkSpecifier.Builder().setSsid("SYNC").setWpa2Passphrase(password).build()
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
            cm.requestNetwork(request,cb,handler,30000)
        } catch(ex: Exception){release();state("Αδυναμία αιτήματος Wi-Fi: ${ex.message}")}
    }

    /** Manual association needs no SSID/location query. The HELLO proves the endpoint. */
    fun useCurrent() {
        // Keep a connection already owned by this app alive while reusing it.
        // Unregistering its request first could make Android disconnect the AP.
        current?.let { network ->
            if(hasEspAddress(cm.getLinkProperties(network))) {
                pendingPassword=null
                state("Χρήση υπάρχουσας σύνδεσης Wi-Fi · επαλήθευση ESP32...")
                ready(network)
                return
            }
        }
        release();val token=generation
        fun select(network: Network,properties: LinkProperties?) {
            if(token!=generation || delivered==network || !hasEspAddress(properties))return
            current=network;delivered=network
            state("Τρέχον Wi-Fi 192.168.4.x · επαλήθευση ESP32...");ready(network)
        }
        val cb=object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties) {select(network,properties)}
            override fun onLost(network: Network) {
                if(token!=generation || current!=network)return
                current=null;delivered=null;lost();state("Χάθηκε η σύνδεση Wi-Fi. Συνδέσου ξανά στο SYNC.")
            }
        }
        callback=cb
        try {
            state("Αναζήτηση συνδεδεμένου Wi-Fi 192.168.4.x... Αν χρειάζεται, σύνδεσε το κινητό στο SYNC από τις ρυθμίσεις.")
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(request,cb,handler)
            for(network in cm.allNetworks) {
                if(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
                    select(network,cm.getLinkProperties(network))
            }
        } catch(ex: Exception){release();state("Αδυναμία πρόσβασης στο τρέχον Wi-Fi: ${ex.message}")}
    }

    private fun release() {
        generation++;pendingPassword=null
        callback?.let { try{cm.unregisterNetworkCallback(it)}catch(_:IllegalArgumentException){} }
        callback=null
        delivered=null
        if(current!=null){current=null;lost()}
    }
    override fun close(){release()}
}
