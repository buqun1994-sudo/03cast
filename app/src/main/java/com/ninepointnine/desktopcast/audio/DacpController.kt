package com.ninepointnine.desktopcast.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    @Volatile private var resolutionGeneration = 0L
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun update(dacpId: String, activeRemote: String) {
        val credentialsChanged = this.dacpId != dacpId || this.activeRemote != activeRemote
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        if (credentialsChanged) {
            resolutionGeneration += 1
            stopDiscovery()
            host = ""
            port = 0
            _discover(resolutionGeneration, dacpId)
        }
    }

    fun canSendCommands(): Boolean =
        host.isNotEmpty() && port > 0 && activeRemote.isNotEmpty()

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun muteToggle() = _send("/ctrl-int/1/mutetoggle")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    fun reset() {
        resolutionGeneration += 1
        stopDiscovery()
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
    }

    fun release() {
        reset()
        exec.shutdownNow()
    }

    private fun _discover(generation: Long, expectedDacpId: String) {
        if (expectedDacpId.isEmpty()) return
        val expectedServiceName = "iTunes_Ctrl_$expectedDacpId"
        var resolving = false
        var resolveAttempt = 0L
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (generation != resolutionGeneration || resolving || host.isNotEmpty() ||
                    !serviceInfo.serviceName.equals(expectedServiceName, ignoreCase = true)
                ) return
                resolving = true
                val attempt = ++resolveAttempt
                resolveService(
                    generation = generation,
                    serviceInfo = serviceInfo,
                    isCurrentAttempt = { attempt == resolveAttempt },
                    onComplete = { resolving = false },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (generation != resolutionGeneration ||
                    !serviceInfo.serviceName.equals(expectedServiceName, ignoreCase = true)
                ) return
                resolveAttempt += 1
                resolving = false
                host = ""
                port = 0
                Log.i(TAG, "DACP service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (discoveryListener === listener) discoveryListener = null
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery start failed: $errorCode")
                if (discoveryListener === listener) discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery stop failed: $errorCode")
                if (discoveryListener === listener) discoveryListener = null
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(DACP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            if (discoveryListener === listener) discoveryListener = null
            Log.w(TAG, "DACP discovery error", e)
        }
    }

    private fun resolveService(
        generation: Long,
        serviceInfo: NsdServiceInfo,
        isCurrentAttempt: () -> Boolean,
        onComplete: () -> Unit,
    ) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    if (generation != resolutionGeneration || !isCurrentAttempt()) return
                    Log.w(TAG, "DACP resolve failed: $code")
                    onComplete()
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    if (generation != resolutionGeneration || !isCurrentAttempt()) return
                    val resolvedHost = si.host.hostAddress
                    if (resolvedHost == null || si.port <= 0) {
                        onComplete()
                        return
                    }
                    host = resolvedHost
                    port = si.port
                    onComplete()
                    Log.i(TAG, "DACP resolved: $host:$port")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
            onComplete()
        }
    }

    private fun stopDiscovery(listener: NsdManager.DiscoveryListener? = discoveryListener) {
        listener ?: return
        if (discoveryListener === listener) discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
            // Discovery may already have stopped or failed to start.
        }
    }

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        val endpointHost = host
        val endpointPort = port
        val remoteToken = activeRemote
        if (endpointHost.isEmpty() || endpointPort <= 0 || remoteToken.isEmpty()) {
            result.setException(IOException("dacp endpoint not resolved"))
            return result
        }
        try {
            exec.execute {
                try {
                    val conn = URL("http", endpointHost, endpointPort, path)
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", remoteToken)
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    try { conn.inputStream.readBytes() } catch (_: Exception) {}
                    conn.disconnect()
                    if (code in 200..299) {
                        result.set(Unit)
                    } else {
                        Log.w(TAG, "DACP $path -> HTTP $code")
                        result.setException(IOException("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                    result.setException(e)
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        return result
    }

    companion object {
        private const val TAG = "DacpController"
        private const val DACP_SERVICE_TYPE = "_dacp._tcp."
    }
}
