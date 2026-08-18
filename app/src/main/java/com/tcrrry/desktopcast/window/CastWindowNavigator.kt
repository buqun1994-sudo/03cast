package com.tcrrry.desktopcast.window

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import com.tcrrry.desktopcast.FullscreenActivity
import com.tcrrry.desktopcast.MainActivity
import com.tcrrry.desktopcast.realDisplaySize
import com.tcrrry.desktopcast.service.CastService

enum class CastWindowMode {
    STANDARD,
    FULLSCREEN,
}

data class CastWindowTransition(
    val target: CastWindowMode,
    val moveSourceTaskToBack: Boolean,
    val reuseTargetTask: Boolean,
    val finishSourceTask: Boolean,
)

object CastWindowPolicy {
    fun transitionFrom(source: CastWindowMode): CastWindowTransition = when (source) {
        CastWindowMode.STANDARD -> CastWindowTransition(
            target = CastWindowMode.FULLSCREEN,
            moveSourceTaskToBack = true,
            reuseTargetTask = false,
            finishSourceTask = false,
        )
        CastWindowMode.FULLSCREEN -> CastWindowTransition(
            target = CastWindowMode.STANDARD,
            moveSourceTaskToBack = false,
            reuseTargetTask = true,
            finishSourceTask = true,
        )
    }
}

/**
 * Public-API task handoff for the car's standard freeform window and a separate
 * fullscreen task. Media lifetime remains in CastService during the handoff.
 */
class CastWindowNavigator(
    private val activity: Activity,
    private val mode: CastWindowMode,
    private val serviceProvider: () -> CastService?,
    private val onFailure: () -> Unit,
) {

    private var outgoingToken: Long? = null

    fun switchMode() {
        if (outgoingToken != null) return
        val service = serviceProvider() ?: return onFailure()
        val transition = CastWindowPolicy.transitionFrom(mode)
        val serviceIntent = Intent(activity, CastService::class.java)
        val token = service.beginWindowHandoff()
        outgoingToken = token
        var sourceMovedToBack = false

        try {
            activity.startService(serviceIntent)
            val nextIntent = targetIntent(transition, token)
            val options = ActivityOptions.makeBasic().setLaunchBounds(targetBounds(transition.target))
            if (transition.moveSourceTaskToBack) {
                sourceMovedToBack = activity.moveTaskToBack(true)
            }
            activity.startActivity(nextIntent, options.toBundle())
            if (transition.finishSourceTask) activity.finishAndRemoveTask()
        } catch (error: RuntimeException) {
            outgoingToken = null
            if (service.cancelWindowHandoff(token)) activity.stopService(serviceIntent)
            if (sourceMovedToBack) restoreSourceTask()
            onFailure()
        }
    }

    fun completeHandoffIfRequested(intent: Intent) {
        val token = intent.getLongExtra(EXTRA_WINDOW_HANDOFF_TOKEN, NO_TOKEN)
        if (token == NO_TOKEN) return
        val service = serviceProvider() ?: return
        if (service.completeWindowHandoff(token)) {
            activity.stopService(Intent(activity, CastService::class.java))
        }
        outgoingToken = null
        intent.removeExtra(EXTRA_WINDOW_HANDOFF_TOKEN)
    }

    /** The source Activity consumes this once from onStop to preserve playback. */
    fun consumeOutgoingHandoff(): Boolean {
        if (outgoingToken == null) return false
        outgoingToken = null
        return true
    }

    fun cancelPendingHandoff() {
        val token = outgoingToken ?: return
        outgoingToken = null
        if (serviceProvider()?.cancelWindowHandoff(token) == true) {
            activity.stopService(Intent(activity, CastService::class.java))
        }
    }

    private fun targetIntent(transition: CastWindowTransition, token: Long): Intent {
        val targetClass = when (transition.target) {
            CastWindowMode.STANDARD -> MainActivity::class.java
            CastWindowMode.FULLSCREEN -> FullscreenActivity::class.java
        }
        val taskFlag = if (transition.reuseTargetTask) {
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        } else {
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        return Intent(activity, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or taskFlag)
            putExtra(EXTRA_WINDOW_HANDOFF_TOKEN, token)
        }
    }

    private fun restoreSourceTask() {
        val sourceClass = when (mode) {
            CastWindowMode.STANDARD -> MainActivity::class.java
            CastWindowMode.FULLSCREEN -> FullscreenActivity::class.java
        }
        val intent = Intent(activity, sourceClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching {
            val options = ActivityOptions.makeBasic().setLaunchBounds(targetBounds(mode))
            activity.startActivity(intent, options.toBundle())
        }
    }

    private fun targetBounds(target: CastWindowMode): Rect {
        val (width, height) = activity.realDisplaySize()
        return when (target) {
            CastWindowMode.FULLSCREEN -> Rect(0, 0, width, height)
            CastWindowMode.STANDARD -> Rect(
                STANDARD_WINDOW_LEFT_PX.coerceAtMost(width - 1),
                STANDARD_WINDOW_TOP_PX.coerceAtMost(height - 1),
                STANDARD_WINDOW_RIGHT_PX.coerceAtMost(width),
                STANDARD_WINDOW_BOTTOM_PX.coerceAtMost(height),
            )
        }
    }

    private companion object {
        const val EXTRA_WINDOW_HANDOFF_TOKEN = "com.tcrrry.desktopcast.extra.WINDOW_HANDOFF_TOKEN"
        const val NO_TOKEN = Long.MIN_VALUE
        const val STANDARD_WINDOW_LEFT_PX = 660
        const val STANDARD_WINDOW_TOP_PX = 90
        const val STANDARD_WINDOW_RIGHT_PX = 1890
        const val STANDARD_WINDOW_BOTTOM_PX = 900
    }
}
