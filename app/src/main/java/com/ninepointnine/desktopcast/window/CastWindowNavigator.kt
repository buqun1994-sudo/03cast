package com.ninepointnine.desktopcast.window

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import com.ninepointnine.desktopcast.FullscreenActivity
import com.ninepointnine.desktopcast.MainActivity
import com.ninepointnine.desktopcast.realDisplaySize
import com.ninepointnine.desktopcast.service.CastService

enum class CastWindowMode {
    STANDARD,
    FULLSCREEN,
}

data class CastWindowTransition(
    val target: CastWindowMode,
    val moveSourceTaskToBack: Boolean,
    val reuseTargetTask: Boolean,
    val retireSourceAfterLaunch: Boolean,
)

object CastWindowPolicy {
    fun transitionFrom(source: CastWindowMode): CastWindowTransition = when (source) {
        CastWindowMode.STANDARD -> CastWindowTransition(
            target = CastWindowMode.FULLSCREEN,
            moveSourceTaskToBack = true,
            reuseTargetTask = false,
            retireSourceAfterLaunch = false,
        )
        CastWindowMode.FULLSCREEN -> CastWindowTransition(
            target = CastWindowMode.STANDARD,
            moveSourceTaskToBack = false,
            reuseTargetTask = true,
            retireSourceAfterLaunch = true,
        )
    }
}

internal fun executeWindowTransition(
    launchTarget: () -> Unit,
    retireSource: () -> Unit,
    onTargetLaunchFailure: (RuntimeException) -> Unit,
    onSourceRetirementFailure: (RuntimeException) -> Unit,
): Boolean {
    try {
        launchTarget()
    } catch (error: RuntimeException) {
        onTargetLaunchFailure(error)
        return false
    }

    try {
        retireSource()
    } catch (error: RuntimeException) {
        onSourceRetirementFailure(error)
    }
    return true
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

    val isTransitionPending: Boolean
        get() = outgoingToken != null

    fun switchMode() {
        if (outgoingToken != null) return
        val service = serviceProvider() ?: return onFailure()
        val transition = CastWindowPolicy.transitionFrom(mode)
        val serviceIntent = Intent(activity, CastService::class.java)
        val token = service.beginWindowHandoff()
        outgoingToken = token
        var sourceMovedToBack = false

        val targetLaunched = executeWindowTransition(
            launchTarget = {
                activity.startService(serviceIntent)
                val nextIntent = targetIntent(transition, token)
                val options = ActivityOptions.makeBasic()
                    .setLaunchBounds(targetBounds(transition.target))
                if (transition.moveSourceTaskToBack) {
                    sourceMovedToBack = activity.moveTaskToBack(true)
                }
                activity.startActivity(nextIntent, options.toBundle())
            },
            retireSource = {
                if (transition.retireSourceAfterLaunch && !activity.isFinishing) {
                    activity.finishAndRemoveTask()
                }
            },
            onTargetLaunchFailure = { error ->
                Log.w(TAG, "Window handoff target launch failed: ${transition.target}", error)
            },
            onSourceRetirementFailure = { error ->
                Log.w(TAG, "Window handoff source retirement failed: $mode", error)
            },
        )
        if (targetLaunched) {
            Log.i(TAG, "Window handoff target launch accepted: $mode -> ${transition.target}, $token")
            return
        }

        outgoingToken = null
        if (service.cancelWindowHandoff(token)) activity.stopService(serviceIntent)
        if (sourceMovedToBack) restoreSourceTask()
        onFailure()
    }

    fun completeHandoffIfRequested(intent: Intent) {
        val token = intent.getLongExtra(EXTRA_WINDOW_HANDOFF_TOKEN, NO_TOKEN)
        if (token == NO_TOKEN) return
        val service = serviceProvider() ?: return
        if (service.completeWindowHandoff(token)) {
            Log.i(TAG, "Window handoff confirmed: $token")
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

    /** Closes every task owned by this package, including a background handoff task. */
    fun finishAllTasks() {
        cancelPendingHandoff()
        val activityManager = activity.getSystemService(Activity.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.appTasks?.toList()?.forEach { task ->
            runCatching { task.finishAndRemoveTask() }
        }
        if (!activity.isFinishing) activity.finishAndRemoveTask()
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
        const val TAG = "CastWindowNavigator"
        const val EXTRA_WINDOW_HANDOFF_TOKEN = "com.ninepointnine.desktopcast.extra.WINDOW_HANDOFF_TOKEN"
        const val NO_TOKEN = Long.MIN_VALUE
        const val STANDARD_WINDOW_LEFT_PX = 660
        const val STANDARD_WINDOW_TOP_PX = 90
        const val STANDARD_WINDOW_RIGHT_PX = 1890
        const val STANDARD_WINDOW_BOTTOM_PX = 900
    }
}
