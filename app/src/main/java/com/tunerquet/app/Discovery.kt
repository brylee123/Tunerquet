package com.tunerquet.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** mDNS discovery of ADB debugging endpoints. A discovered endpoint is NOT authenticated. */
class Discovery(context: Context, private val onFound: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val types = listOf("_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.", "_adb._tcp.")
    fun start() {
        stop()
        types.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            onFound("${info.serviceName} · $host:${info.port} · $type")
                        }
                    })
                }
            }
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); listeners.add(listener) }
            catch (_: Exception) { }
        }
    }
    fun stop() {
        listeners.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listeners.clear()
    }
}
