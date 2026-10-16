package dev.jasonpearson.motiondots.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet6Address
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredMac(val name: String, val host: String, val port: Int)

/**
 * Bonjour/mDNS discovery of `_motiondots._tcp.`. All internal state is confined to the main
 * thread; [start] and [stop] may be called from any thread.
 *
 * API 34+: `registerServiceInfoCallback` (concurrent, tracks address changes).
 * Older: deprecated `resolveService(info, listener)`, one at a time via [resolveQueue].
 */
class MacDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    private val _macs = MutableStateFlow<List<DiscoveredMac>>(emptyList())
    val macs: StateFlow<List<DiscoveredMac>> = _macs.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** One-line discovery problem for the UI (for example no usable IPv4 address). */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, DiscoveredMac>()

    // API 34+
    private val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

    // Pre 34: NsdManager allows a single outstanding resolve.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val resolveRetries = HashMap<String, Int>()

    fun start() = onMain { startOnMain() }

    fun stop() = onMain { stopOnMain() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun startOnMain() {
        if (running) return
        running = true
        _problem.value = null
        try {
            multicastLock =
                wifi?.createMulticastLock("motiondots-mdns")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed", e)
        }
        val listener = DiscoveryCallbacks()
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed", e)
            stopOnMain()
        }
    }

    private fun stopOnMain() {
        if (!running) return
        running = false
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.d(TAG, "stopServiceDiscovery: ${e.message}")
            }
        }
        discoveryListener = null
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.values.forEach { unregisterQuietly(it) }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
        resolveRetries.clear()
        resolving = false
        _problem.value = null
        found.clear()
        _macs.value = emptyList()
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicast release failed", e)
        }
        multicastLock = null
    }

    private fun publish() {
        _macs.value = found.values.toList()
    }

    private fun onFound(info: NsdServiceInfo) {
        if (!running) return
        if (Build.VERSION.SDK_INT >= 34) watch(info) else enqueueResolve(info)
    }

    private fun onLost(name: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.remove(name)?.let { unregisterQuietly(it) }
        }
        resolveQueue.removeAll { it.serviceName == name }
        if (found.remove(name) != null) publish()
    }

    private fun accept(name: String, host: String?, port: Int) {
        if (!running || host == null || port <= 0) return
        found[name] = DiscoveredMac(name, host, port)
        publish()
    }

    // ---- API 34+ ----

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (infoCallbacks.containsKey(name)) return
        val cb =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    infoCallbacks.remove(name)
                    Log.w(TAG, "info callback registration failed: $errorCode")
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    accept(name, AddressPicker.pick(serviceInfo.hostAddresses), serviceInfo.port)
                }

                override fun onServiceLost() {
                    if (infoCallbacks.remove(name) != null) {
                        if (found.remove(name) != null) publish()
                    }
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
        infoCallbacks[name] = cb
        try {
            nsd.registerServiceInfoCallback(info, mainExecutor, cb)
        } catch (e: Exception) {
            infoCallbacks.remove(name)
            Log.w(TAG, "registerServiceInfoCallback failed", e)
        }
    }

    private fun unregisterQuietly(cb: NsdManager.ServiceInfoCallback) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            nsd.unregisterServiceInfoCallback(cb)
        } catch (e: Exception) {
            Log.d(TAG, "unregisterServiceInfoCallback: ${e.message}")
        }
    }

    // ---- pre API 34 ----

    private fun enqueueResolve(info: NsdServiceInfo) {
        if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info)
        pumpResolve()
    }

    @Suppress("DEPRECATION")
    private fun pumpResolve() {
        if (resolving || !running) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        retryResolve(serviceInfo, ipv6LinkLocalOnly = false)
                        pumpResolve()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val address = serviceInfo.host
                        if (address is Inet6Address && address.isLinkLocalAddress) {
                            // Pre API 34 resolve returns a single address. fe80:: needs a scope id
                            // that a ws:// URL cannot carry, so it is unusable: resolve again, in
                            // case the next answer carries the IPv4 record.
                            retryResolve(serviceInfo, ipv6LinkLocalOnly = true)
                        } else {
                            resolveRetries.remove(serviceInfo.serviceName)
                            val host = address?.let { AddressPicker.pick(listOf(it)) }
                            accept(serviceInfo.serviceName, host, serviceInfo.port)
                        }
                        pumpResolve()
                    }
                }
            }
        try {
            nsd.resolveService(next, listener)
        } catch (e: Exception) {
            Log.w(TAG, "resolveService failed", e)
            resolving = false
            pumpResolve()
        }
    }

    /** Re-queues a failed or unusable resolve, at most [MAX_RESOLVE_RETRIES] times per service. */
    private fun retryResolve(serviceInfo: NsdServiceInfo, ipv6LinkLocalOnly: Boolean) {
        val name = serviceInfo.serviceName
        val tries = (resolveRetries[name] ?: 0) + 1
        resolveRetries[name] = tries
        if (running && tries <= MAX_RESOLVE_RETRIES) {
            main.postDelayed({ if (running) enqueueResolve(serviceInfo) }, 500L)
        } else if (running && ipv6LinkLocalOnly) {
            _problem.value = "Couldn't get an IPv4 address for $name, enter host:port manually"
        }
    }

    private inner class DiscoveryCallbacks : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery failed: $errorCode")
            main.post { if (discoveryListener === this) stopOnMain() }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "stop discovery failed: $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            main.post { if (discoveryListener === this) onFound(serviceInfo) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            main.post { if (discoveryListener === this) onLost(serviceInfo.serviceName) }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_motiondots._tcp."
        private const val TAG = "MacDiscovery"
        private const val MAX_RESOLVE_RETRIES = 3
    }
}
