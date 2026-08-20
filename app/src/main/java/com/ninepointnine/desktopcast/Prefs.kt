package com.ninepointnine.desktopcast

import android.media.MediaFormat

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"

    const val DEF_SERVER_NAME = "03投屏"
    const val DRIVING_PLAYBACK_GUARD = "driving_playback_guard"
    const val DEF_DRIVING_PLAYBACK_GUARD = true
    const val FALLBACK_MAC_ADDRESS = "fallback_mac_address"
    const val AIRPLAY_DISPLAY_UUID = "airplay_display_uuid"
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    const val LOW_LATENCY = "low_latency"; const val DEF_LOW_LATENCY = false
    const val AUDIO_AUTO_BUFFER = "audio_auto_buffer"; const val DEF_AUDIO_AUTO_BUFFER = true
    // fixed cushion ms, used only when AUDIO_AUTO_BUFFER is off
    const val AUDIO_CUSHION_MS = "audio_cushion_ms"; const val DEF_AUDIO_CUSHION_MS = 40
    // slider step 0..4 mapping to arrival-delay percentile the cushion targets;
    // lower = less latency, higher = more stable
    const val AUDIO_ADAPTIVE_STEP = "audio_adaptive_step"; const val DEF_AUDIO_ADAPTIVE_STEP = 3
    val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)
    const val OBOE_BUFFER_FRAMES = "oboe_buffer_frames"; const val DEF_OBOE_BUFFER_FRAMES = 0
    const val FORCE_SW_ALAC = "force_sw_alac"; const val DEF_FORCE_SW_ALAC = true
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
}
