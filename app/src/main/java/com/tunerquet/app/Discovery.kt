package com.tunerquet.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

enum class EndpointKind(val serviceType: String, val label: String) {
    CONNECT("_adb-tls-connect._tcp.", "Secure connection"),
    PAIRING("_adb-tls-pairing._tcp.", "Pairing service"),
    LEGACY("_adb._tcp.", "Legacy ADB")
}

/** An advertisement is not authorization to connect. */
data class DebugEndpoint(val id: String, val name: String, val host: String, val port: Int, val kind: EndpointKind) {
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

data class ScanState(
    val scanning: Boolean = false,
    val endpoints: List<DebugEndpoint> = emptyList(),
    val errors: List<String> = emptyList(),
    val message: String = "Ready to scan"
)

/** All state and observer callbacks are confined to the main looper. */
class Discovery(context: Context, private val onChanged: (ScanState) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val endpoints = linkedMapOf<String, DebugEndpoint>()
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val queue = ArrayDeque<Triple<String, NsdServiceInfo, EndpointKind>>()
    private val errors = linkedSetOf<String>()
    private var generation = 0
    private var scanning = false
    private var resolving = false
    private var message = "Ready to scan"
    private val timeout = Runnable { stop("Scan complete. Scan again to refresh results.") }

    private fun publish() = onChanged(ScanState(scanning, endpoints.values.toList(), errors.toList(), message))
    private fun error(text: String) { errors.add(text); publish() }

    fun start() {
        stop()
        generation++
        val run = generation
        endpoints.clear()
        services.clear()
        errors.clear()
        scanning = true
        message = "Scanning for 30 seconds…"
        publish()
        handler.postDelayed(timeout, 30_000)
        EndpointKind.entries.forEach { kind ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    handler.post {
                        if (run == generation && scanning) {
                            listeners.remove(this)
                            error("${kind.label} discovery failed (code $errorCode). Check the network and try again.")
                            if (listeners.isEmpty()) stop("Discovery could not start. Try scanning again.")
                        }
                    }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    handler.post {
                        // Preserve the listener so a later stop can retry cleanup.
                        if (this !in listeners) listeners.add(this)
                        error("Could not stop ${kind.label} discovery (code $errorCode).")
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (run == generation && scanning) {
                            val id = "${kind.name}:${serviceInfo.serviceName}"
                            services.remove(id)
                            endpoints.remove(id)
                            queue.removeAll { it.first == id }
                            publish()
                        }
                    }
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (run == generation && scanning) {
                            val id = "${kind.name}:${serviceInfo.serviceName}"
                            if (id !in services) {
                                services[id] = serviceInfo
                                queue.addLast(Triple(id, serviceInfo, kind))
                                resolveNext()
                            }
                        }
                    }
                }
            }
            listeners.add(listener)
            try {
                nsd.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                listeners.remove(listener)
                error("${kind.label} discovery unavailable (${e.javaClass.simpleName}).")
            }
        }
        if (listeners.isEmpty()) stop("Discovery could not start. Try scanning again.")
    }

    @Suppress("DEPRECATION") // API 26 compatibility; serialize resolutions for older NSD implementations.
    private fun resolveNext() {
        if (!scanning || resolving || queue.isEmpty()) return
        val (id, service, kind) = queue.removeFirst()
        val run = generation
        resolving = true
        fun complete(info: NsdServiceInfo?, failure: String?) {
            handler.post {
                resolving = false
                if (run == generation && scanning && services[id] === service) {
                    val host = info?.host?.hostAddress
                    if (host != null && info.port in 1..65535) {
                        endpoints[id] = DebugEndpoint(id, info.serviceName, host, info.port, kind)
                        publish()
                    } else {
                        services.remove(id)
                        error(failure ?: "${kind.label} returned an invalid address.")
                    }
                }
                resolveNext()
            }
        }
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                    complete(null, "Could not resolve ${kind.label} (code $errorCode). Try scanning again.")
                override fun onServiceResolved(info: NsdServiceInfo) = complete(info, null)
            })
        } catch (e: RuntimeException) {
            complete(null, "Could not resolve ${kind.label} (${e.javaClass.simpleName}).")
        }
    }

    fun stop(reason: String = "Scan stopped") {
        generation++ // Ignore callbacks from stopped scans and old resolutions.
        scanning = false
        message = reason
        handler.removeCallbacks(timeout)
        queue.clear()
        services.clear()
        val stopping = listeners.toList()
        listeners.clear()
        stopping.forEach {
            try { nsd.stopServiceDiscovery(it) }
            catch (_: IllegalArgumentException) { /* Already unregistered by Android. */ }
            catch (e: RuntimeException) { errors.add("Could not stop discovery (${e.javaClass.simpleName}).") }
        }
        publish()
    }
}
