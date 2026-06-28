package com.game.keymousepro.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Tự động tìm ADB Wireless Debugging qua NSD/mDNS.
 * Không cần nhập IP hay port thủ công.
 */
class AutoDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoDiscovery"
        private const val SERVICE_PAIR = "_adb-tls-pairing._tcp."
        private const val SERVICE_CONN = "_adb-tls-connect._tcp."
        private const val TIMEOUT_MS   = 45_000L
    }

    data class ServiceInfo(val host: String, val port: Int, val name: String = "")

    sealed class DiscoveryResult {
        data class Found(val service: ServiceInfo) : DiscoveryResult()
        object Timeout  : DiscoveryResult()
        data class Error(val message: String) : DiscoveryResult()
    }

    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private var multicastLock: WifiManager.MulticastLock? = null
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    // ── Multicast Lock (bắt buộc cho mDNS trên WiFi) ──────────────

    private fun acquireLock() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("kmp_nsd")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock: ${e.message}")
        }
    }

    private fun releaseLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
        } catch (e: Exception) {}
    }

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Tìm service ghép nối (_adb-tls-pairing._tcp).
     * User phải mở "Ghép nối thiết bị bằng mã" trong Wireless Debugging.
     */
    suspend fun discoverPairingService(): DiscoveryResult =
        withContext(Dispatchers.IO) { discover(SERVICE_PAIR, TIMEOUT_MS) }

    /**
     * Tìm service kết nối (_adb-tls-connect._tcp) — sau khi pair xong.
     */
    suspend fun discoverConnectService(): DiscoveryResult =
        withContext(Dispatchers.IO) { discover(SERVICE_CONN, 15_000L) }

    private suspend fun discover(serviceType: String, timeoutMs: Long): DiscoveryResult {
        acquireLock()
        val channel = Channel<ServiceInfo?>(1)
        val listener = buildListener(channel)
        activeListeners.add(listener)

        return try {
            nsdManager.discoverServices(
                serviceType, NsdManager.PROTOCOL_DNS_SD, listener
            )
            Log.d(TAG, "Discovering: $serviceType")

            val result = withTimeoutOrNull(timeoutMs) { channel.receive() }

            if (result == null) {
                Log.w(TAG, "Timeout for $serviceType")
                DiscoveryResult.Timeout
            } else {
                Log.d(TAG, "Found: ${result.host}:${result.port}")
                DiscoveryResult.Found(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error: ${e.message}")
            DiscoveryResult.Error(e.message ?: "Lỗi không xác định")
        } finally {
            stopListener(listener)
            releaseLock()
        }
    }

    private fun buildListener(channel: Channel<ServiceInfo?>): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(st: String, code: Int) {
                Log.e(TAG, "Start failed: $code")
                channel.trySend(null)
            }
            override fun onStopDiscoveryFailed(st: String, code: Int) {}
            override fun onDiscoveryStarted(st: String) { Log.d(TAG, "Started: $st") }
            override fun onDiscoveryStopped(st: String) { Log.d(TAG, "Stopped: $st") }
            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "Found: ${info.serviceName}")
                try {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                            Log.w(TAG, "Resolve failed: $code")
                        }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            val host = i.host?.hostAddress ?: "127.0.0.1"
                            val port = i.port
                            Log.d(TAG, "Resolved: $host:$port")
                            channel.trySend(ServiceInfo(host, port, i.serviceName))
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Resolve error: ${e.message}")
                }
            }
        }
    }

    private fun stopListener(listener: NsdManager.DiscoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(listener)
            activeListeners.remove(listener)
        } catch (_: Exception) {}
    }

    fun stopAll() {
        activeListeners.toList().forEach { stopListener(it) }
        releaseLock()
    }
}
