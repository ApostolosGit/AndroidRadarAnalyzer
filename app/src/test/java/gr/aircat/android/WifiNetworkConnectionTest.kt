package gr.aircat.android

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiNetworkConnectionTest {
    private fun network(id: Int): Network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply{isAccessible=true}.newInstance(id)
    private fun properties(ip: String): LinkProperties {
        // These framework builders are hidden from the public Android SDK;
        // only the JVM test uses reflection to construct callback fixtures.
        val address=LinkAddress::class.java.getDeclaredConstructor(String::class.java)
            .apply{isAccessible=true}.newInstance(ip)
        return LinkProperties().also { properties ->
            LinkProperties::class.java.getDeclaredMethod("addLinkAddress",LinkAddress::class.java)
                .apply{isAccessible=true}.invoke(properties,address)
        }
    }
    private fun callback(c: WifiNetworkConnection)=WifiNetworkConnection::class.java.getDeclaredField("callback")
        .apply{isAccessible=true}.get(c) as ConnectivityManager.NetworkCallback
    private fun grant(activity: MainActivity) {
        shadowOf(activity.application).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.CHANGE_NETWORK_STATE,Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,Manifest.permission.CHANGE_WIFI_STATE,Manifest.permission.INTERNET)
    }
    @Test fun automaticWaitsForDhcpAndIgnoresStaleCallbacksAfterClose() {
        val activityController=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=activityController.get();grant(activity)
        val ready=ArrayList<Network>();var losses=0
        val c=WifiNetworkConnection(activity,{}, {ready.add(it)}, {losses++})
        try {
            c.automatic("example-password")
            val cb=callback(c);val n=network(123)
            cb.onAvailable(n);assertTrue(ready.isEmpty())
            cb.onLinkPropertiesChanged(n,properties("192.168.1.2/24"));assertTrue(ready.isEmpty())
            cb.onLinkPropertiesChanged(n,properties("192.168.4.2/24"));assertEquals(listOf(n),ready)
            cb.onLinkPropertiesChanged(n,properties("192.168.4.2/24"));assertEquals(1,ready.size)
            cb.onLost(n);assertEquals(1,losses)
            val n2=network(124)
            cb.onAvailable(n2);cb.onLinkPropertiesChanged(n2,properties("192.168.4.3/24"))
            assertEquals(2,ready.size)
            c.close();assertEquals(2,losses)
            cb.onAvailable(network(125));cb.onLinkPropertiesChanged(network(125),properties("192.168.4.4/24"))
            assertEquals(2,ready.size)
        } finally{c.close();activityController.pause().stop().destroy()}
    }
    @Test fun reusingOwnedWifiKeepsItsNetworkRequestRegistered() {
        val activityController=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=activityController.get();grant(activity)
        var ready=0;var losses=0
        val c=WifiNetworkConnection(activity,{}, {ready++}, {losses++})
        try {
            c.automatic("example-password")
            val cb=callback(c);val n=network(321);val ip=properties("192.168.4.2/24")
            cb.onAvailable(n);cb.onLinkPropertiesChanged(n,ip)
            val cm=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            shadowOf(cm).setLinkProperties(n,ip)
            c.useCurrent()
            assertSame(cb,callback(c));assertEquals(2,ready);assertEquals(0,losses)
        } finally{c.close();activityController.pause().stop().destroy()}
    }
    @Test fun manualModeValidatesSubnetAndCanReconnectWithoutLocationQuery() {
        val activityController=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=activityController.get()
        var ready=0
        val c=WifiNetworkConnection(activity,{}, {ready++}, {})
        try {
            c.useCurrent();val cb=callback(c);val n=network(400)
            cb.onLinkPropertiesChanged(n,properties("10.0.0.2/24"));assertEquals(0,ready)
            cb.onLinkPropertiesChanged(n,properties("192.168.4.2/24"));assertEquals(1,ready)
            cb.onLost(n)
            cb.onLinkPropertiesChanged(network(401),properties("192.168.4.3/24"));assertEquals(2,ready)
        } finally{c.close();activityController.pause().stop().destroy()}
    }
}
