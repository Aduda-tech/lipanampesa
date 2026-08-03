package com.lipanampesa.shop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Finds the shop backend on the local network (Wi-Fi/hotspot) using mDNS / DNS-SD.
 * The backend advertises itself as "_mpesa-shop._tcp.local." (see backend/src/discovery.js).
 * Result: the server URL auto-fills on the login screen — no typing IPs, no setup.
 * Works over plain LAN: no internet bundles required on the phone.
 */
class ServerDiscovery(
    context: Context,
    private val onFound: (String) -> Unit,
    private val onNotFound: () -> Unit
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var finished = false

    fun start(timeoutMs: Long = 9000) {
        finished = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            if (!host.isNullOrEmpty()) finish("http://$host:${info.port}/")
                        }
                    })
                } catch (e: Exception) { /* resolution raced with stop() — ignore */ }
            }
        }
        listener = l
        handler.postDelayed({ finish(null) }, timeoutMs)
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            finish(null)
        }
    }

    private fun finish(url: String?) {
        if (finished) return
        finished = true
        stop()
        handler.post {
            if (url != null) onFound(url) else onNotFound()
        }
    }

    fun stop() {
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (e: Exception) { /* already stopped */ }
        }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_mpesa-shop._tcp."
    }
}
