package com.tcrrry.desktopcast.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import com.tcrrry.desktopcast.Prefs
import com.tcrrry.desktopcast.bridge.NativeBridge
import com.tcrrry.desktopcast.discovery.NsdServiceManager
import com.tcrrry.desktopcast.dlna.DlnaRenderer
import com.tcrrry.desktopcast.realDisplaySize
import com.tcrrry.desktopcast.renderer.VideoRenderer
import com.tcrrry.desktopcast.renderer.codecSummaryForRuntime
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

/**
 * Owns the protocol listeners and their platform resources. It deliberately
 * knows nothing about UI state or individual playback commands.
 */
class CastReceiverRuntime(
    private val context: Context,
    private val audioManager: AudioManager,
    private val preferences: SharedPreferences,
    private val playback: CastPlaybackRouter,
    private val isStarted: () -> Boolean,
    private val onFailure: (Throwable) -> Unit,
) {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var dlnaRenderer: DlnaRenderer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentAddress: Inet4Address? = null
    private var protocolGeneration = 0

    init {
        bindPlaybackCallbacks()
    }

    val address: Inet4Address? get() = currentAddress

    val isReady: Boolean
        get() = nativeHandle != 0L && dlnaRenderer?.isRunning == true

    fun start(address: Inet4Address) {
        if (address == currentAddress && isReady) return
        stop()
        bindPlaybackCallbacks()
        currentAddress = address
        val generation = ++protocolGeneration
        try {
            val hardwareAddress = hardwareAddress(address)
            startAirPlay(address, hardwareAddress, generation)
            startDlna(address, hardwareAddress, generation)
            acquireWakeLock()
        } catch (error: Throwable) {
            Log.e(TAG, "Receiver startup failed", error)
            stop()
            throw error
        }
    }

    fun stop() {
        protocolGeneration += 1
        currentAddress = null
        playback.stopOutputs()

        dlnaRenderer?.stop()
        dlnaRenderer = null
        nsdManager?.release()
        nsdManager = null
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun bindPlaybackCallbacks() {
        playback.onDropAirPlayConnections = {
            if (nativeHandle != 0L) NativeBridge.nativeDropConnections(nativeHandle)
        }
        playback.onDlnaTransportChanged = {
            dlnaRenderer?.publishTransportChanged()
        }
    }

    private fun startAirPlay(
        address: Inet4Address,
        hardwareAddress: ByteArray,
        generation: Int,
    ) {
        val handle = NativeBridge.nativeInit(
            playback.airPlayCallbacks,
            hardwareAddress,
            Prefs.DEF_SERVER_NAME,
            context.filesDir.resolve("airplay.pem").absolutePath,
            true,
            false,
        )
        check(handle != 0L) { "AirPlay native initialization failed" }
        nativeHandle = handle
        NativeBridge.nativeSetDisplayUuid(handle, displayUuid(hardwareAddress))

        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
        )
        playback.attachAirPlay(handle, readAudioConfig(preferences))

        val panel = context.realDisplaySize()
        val decoderProfile = VideoRenderer.resolveMirrorDisplayProfile(
            width = panel.first,
            height = panel.second,
            displayRefreshHz = DISPLAY_REFRESH_HZ,
        )
        playback.configureMirrorDecoder(decoderProfile)
        NativeBridge.nativeSetH265Enabled(handle, decoderProfile.supportsH265)
        NativeBridge.nativeSetCodecs(handle, alac = true, aac = true)
        NativeBridge.nativeSetHlsEnabled(handle, true)
        NativeBridge.nativeSetAudioEnabled(handle, true)
        NativeBridge.nativeSetPlist(handle, "overscanned", 0)
        NativeBridge.nativeSetPlist(handle, "maxFPS", decoderProfile.advertisedMaxFrameRate)
        // Advertise the physical panel to AirPlay so senders do not fall back
        // to a small compatibility mode. Decoder dimensions come only from the
        // current RTP stream; seeding them here would leak the previous
        // sender's shape into the next mirror session.
        NativeBridge.nativeSetDisplaySize(handle, panel.first, panel.second, DISPLAY_REFRESH_HZ)
        Log.i(
            TAG,
            "AirPlay display profile: panel=${panel.first}x${panel.second}, " +
                "advertised=${panel.first}x${panel.second}, " +
                "maxFPS=${decoderProfile.advertisedMaxFrameRate}, " +
                "h265=${decoderProfile.supportsH265}, " +
                "codecCandidates=${decoderProfile.codecSummaryForRuntime()}",
        )

        val port = NativeBridge.nativeStart(handle, AIRPLAY_PORT)
        check(port > 0) { "AirPlay TCP $AIRPLAY_PORT failed to bind on ${address.hostAddress}" }
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(handle).orEmpty()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(handle).orEmpty()
        val raopName = NativeBridge.nativeGetRaopServiceName(handle) ?: Prefs.DEF_SERVER_NAME
        val airplayName = NativeBridge.nativeGetServerName(handle) ?: Prefs.DEF_SERVER_NAME

        nsdManager = NsdServiceManager(context).also { manager ->
            manager.acquireMulticastLock()
            manager.registerAirPlayPair(
                raopName = raopName,
                airplayName = airplayName,
                port = port,
                raopTxt = raopTxt,
                airplayTxt = airplayTxt,
            ) { result ->
                if (result.complete) {
                    Log.i(TAG, "AirPlay mDNS pair registered")
                } else if (isStarted() && generation == protocolGeneration) {
                    Log.e(TAG, "AirPlay mDNS incomplete: ${result.failures}")
                    onFailure(IllegalStateException("AirPlay mDNS registration incomplete"))
                }
            }
        }
    }

    private fun startDlna(
        address: Inet4Address,
        hardwareAddress: ByteArray,
        generation: Int,
    ) {
        dlnaRenderer = DlnaRenderer(
            context = context.applicationContext,
            friendlyName = Prefs.DEF_SERVER_NAME,
            uuid = DlnaRenderer.stableUuid(hardwareAddress),
            controller = playback.dlnaController,
            onFailure = { error ->
                if (isStarted() && generation == protocolGeneration) {
                    Log.e(TAG, "DLNA listener failed", error)
                    onFailure(error)
                }
            },
        ).also { it.start(address) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "03cast:receiver").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun hardwareAddress(address: Inet4Address): ByteArray {
        runCatching { NetworkInterface.getByInetAddress(address)?.hardwareAddress }
            .getOrNull()
            ?.takeIf(::usableMac)
            ?.let { return it }
        val saved = preferences.getString(Prefs.FALLBACK_MAC_ADDRESS, null)
            ?.split(':')
            ?.mapNotNull { it.toIntOrNull(16)?.toByte() }
            ?.toByteArray()
            ?.takeIf(::usableMac)
        if (saved != null) return saved
        val generated = ByteArray(6).also(SecureRandom()::nextBytes)
        generated[0] = ((generated[0].toInt() and 0xf0) or 0x0a).toByte()
        preferences.edit().putString(
            Prefs.FALLBACK_MAC_ADDRESS,
            generated.joinToString(":") { "%02x".format(it) },
        ).apply()
        return generated
    }

    private fun usableMac(value: ByteArray?): Boolean =
        value != null && value.size == 6 && value.any { it != 0.toByte() }

    private fun displayUuid(hardwareAddress: ByteArray): String {
        preferences.getString(Prefs.AIRPLAY_DISPLAY_UUID, null)
            ?.let { saved -> runCatching { UUID.fromString(saved) }.getOrNull()?.let { return it.toString() } }
        val generated = UUID.nameUUIDFromBytes(
            ("desktopcast-display-v2:" + hardwareAddress.joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }).toByteArray(StandardCharsets.US_ASCII),
        ).toString()
        preferences.edit().putString(Prefs.AIRPLAY_DISPLAY_UUID, generated).apply()
        return generated
    }

    private companion object {
        const val TAG = "CastReceiverRuntime"
        const val AIRPLAY_PORT = 7000
        const val DISPLAY_REFRESH_HZ = 60
    }
}
