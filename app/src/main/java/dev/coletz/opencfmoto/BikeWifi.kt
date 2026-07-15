package dev.coletz.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine).
 *
 * QR for this bike:
 *   ssid = CFMOTO-f46457
 *   pwd  = 59a9cddc94
 *   auth = wpa2-psk
 */
object BikeWifi {
    private var callback: ConnectivityManager.NetworkCallback? = null
    var currentNetwork: Network? = null
        private set

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                cm.bindProcessToNetwork(network)
                log("Wi-Fi joined: $ssid (network=$network, bound)")
                onAvailable(network)
            }

            override fun onLost(network: Network) {
                log("Wi-Fi lost: $network")
                currentNetwork = null
                onLost()
            }

            override fun onUnavailable() {
                log("Wi-Fi join unavailable (user declined or out of range)")
                onUnavailable()
            }
        }
        callback = cb
        log("requesting Wi-Fi join: $ssid …")
        cm.requestNetwork(request, cb)
    }

    fun leave(context: Context, log: (String) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        cm.bindProcessToNetwork(null)
        currentNetwork = null
        log("Wi-Fi released")
    }
}
