package com.fingertipsmcp.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the device's current LAN (Wi-Fi or Ethernet) IPv4 address. */
class NetworkWatcher(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val addresses = ConcurrentHashMap<Network, InetAddress>()
    private val lan = MutableStateFlow<InetAddress?>(null)

    val lanAddress: StateFlow<InetAddress?> = lan.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                val v4 = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }
                if (v4 == null) addresses.remove(network) else addresses[network] = v4
                publish()
            }

            override fun onLost(network: Network) {
                addresses.remove(network)
                publish()
            }
        }

    fun start() {
        val request =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    private fun publish() {
        lan.value = addresses.values.sortedBy { it.hostAddress }.firstOrNull()
    }
}
