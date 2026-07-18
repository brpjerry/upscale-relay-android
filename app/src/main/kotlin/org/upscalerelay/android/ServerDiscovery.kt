package org.upscalerelay.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** A relay server advertised over mDNS/DNS-SD on the local network. */
data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int,
)

/**
 * Browses `_upscalerelay._tcp` with Android NSD. Discovery augments — never
 * replaces — manual host entry: results only feed a suggestion list.
 *
 * NsdManager can only resolve one service at a time, so found services are
 * queued and resolved serially.
 */
class ServerDiscovery(context: Context) {
    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mutableServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = mutableServers.asStateFlow()

    private val started = AtomicBoolean(false)
    private val lock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed to start: $errorCode")
                started.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE) return
                synchronized(lock) {
                    resolveQueue.addLast(service)
                    resolveNextLocked()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                mutableServers.value =
                    mutableServers.value.filterNot { it.serviceName == service.serviceName }
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "NSD discovery unavailable: ${it.message}")
                started.set(false)
                discoveryListener = null
            }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(lock) {
            resolveQueue.clear()
            resolving = false
        }
        mutableServers.value = emptyList()
    }

    private fun resolveNextLocked() {
        if (resolving) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34; minSdk is 29.
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) {
                    resolving = false
                    resolveNextLocked()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null && serviceInfo.port > 0) {
                    val entry = DiscoveredServer(
                        serviceName = serviceInfo.serviceName,
                        host = host,
                        port = serviceInfo.port,
                    )
                    mutableServers.value =
                        mutableServers.value.filterNot { it.serviceName == entry.serviceName } + entry
                }
                synchronized(lock) {
                    resolving = false
                    resolveNextLocked()
                }
            }
        })
    }

    companion object {
        private const val TAG = "RelayDiscovery"
        const val SERVICE_TYPE = "_upscalerelay._tcp"
    }
}
