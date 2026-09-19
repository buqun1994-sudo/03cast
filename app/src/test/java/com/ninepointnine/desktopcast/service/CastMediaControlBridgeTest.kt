package com.ninepointnine.desktopcast.service

import android.os.Looper
import androidx.media3.common.Player
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastPhase
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaControlBridgeTest {
    @Test
    fun handleSeekClampsAndForwardsPositionOnce() {
        val positions = mutableListOf<Long>()
        val bridge = bridge { positions += it }

        invokeHandleSeek(bridge, -1L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        invokeHandleSeek(bridge, 12_345L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

        assertEquals(listOf(0L, 12_345L), positions)
    }

    @Test
    fun handleSeekIgnoresOtherCommands() {
        val positions = mutableListOf<Long>()
        val bridge = bridge { positions += it }

        invokeHandleSeek(bridge, 12_345L, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)

        assertTrue(positions.isEmpty())
    }

    @Test
    fun gesturePreviewIsTheOnlyDisplayedPositionUntilCleared() {
        val bridge = bridge {}

        assertEquals(12_000L, bridge.displayPositionMs())
        bridge.setPositionPreview(48_000L)
        assertEquals(48_000L, bridge.displayPositionMs())
        bridge.setPositionPreview(null)
        assertEquals(12_000L, bridge.displayPositionMs())
    }

    private fun bridge(onSeekRequested: (Long) -> Unit) = CastMediaControlBridge(
        looper = newTestLooper(),
        snapshot = {
            CastSessionState(
                phase = CastPhase.PLAYING,
                protocol = CastProtocol.DLNA,
                content = CastContentKind.NETWORK_VIDEO,
                positionMs = 12_000L,
                durationMs = 60_000L,
                canSeek = true,
            )
        },
        setPlaying = {},
        onSeekRequested = onSeekRequested,
    )

    private fun invokeHandleSeek(
        bridge: CastMediaControlBridge,
        positionMs: Long,
        seekCommand: Int,
    ) {
        HANDLE_SEEK.invoke(bridge, 0, positionMs, seekCommand)
    }

    private companion object {
        val HANDLE_SEEK = CastMediaControlBridge::class.java.getDeclaredMethod(
            "handleSeek",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        fun newTestLooper(): Looper = Looper::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
    }
}
