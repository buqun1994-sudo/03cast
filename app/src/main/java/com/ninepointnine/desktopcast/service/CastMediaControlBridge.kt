package com.ninepointnine.desktopcast.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastPhase
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionState

/** Exposes router-owned state to Media3's public control component. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CastMediaControlBridge(
    looper: Looper,
    private val snapshot: () -> CastSessionState,
    private val setPlaying: (Boolean) -> Unit,
    private val onSeekRequested: (Long) -> Unit,
) : SimpleBasePlayer(looper) {

    private var positionPreviewMs: Long? = null

    fun refresh() = invalidateState()

    /**
     * Overrides only the position exposed to PlayerControlView while a gesture is active.
     * Playback itself is not seeked until the Activity commits the gesture once.
     */
    fun setPositionPreview(positionMs: Long?) {
        positionPreviewMs = positionMs?.coerceAtLeast(0L)
    }

    internal fun displayPositionMs(): Long =
        positionPreviewMs ?: snapshot().positionMs.coerceAtLeast(0L)

    override fun getState(): State {
        val state = snapshot()
        if (state.phase !in ACTIVE_PHASES) {
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .build()
        }

        val hasPlaybackControl = state.content !in setOf(
            CastContentKind.MIRROR,
            CastContentKind.IMAGE,
        )
        val canSeek = state.canSeek && !(
            state.protocol == CastProtocol.AIRPLAY && state.content == CastContentKind.AUDIO
        )
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .apply {
                if (hasPlaybackControl) add(Player.COMMAND_PLAY_PAUSE)
                if (canSeek) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(state.title)
            .setArtist(state.detail)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(MEDIA_ITEM_UID)
                        .setMediaMetadata(metadata)
                        .setDurationUs(
                            if (state.durationMs > 0L) state.durationMs * 1000L else C.TIME_UNSET,
                        )
                        .setIsSeekable(canSeek)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(state.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier { displayPositionMs() })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) {
            onSeekRequested(positionMs.coerceAtLeast(0L))
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MEDIA_ITEM_UID = "active-cast"
        val ACTIVE_PHASES = setOf(
            CastPhase.PLAYING,
            CastPhase.MIRRORING,
            CastPhase.AUDIO,
        )
    }
}
