package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommercialLayoutContractTest {
    @Test
    fun settingsExposeEntitlementAboutAndWaitingLayouts() {
        val appDirectory = findAppDirectory()
        val activity = File(appDirectory, "src/main/res/layout/activity_main.xml").readText()
        val strings = File(appDirectory, "src/main/res/values/strings.xml").readText()
        val commercial = File(
            appDirectory,
            "src/main/res/layout/content_settings_commercial.xml",
        ).readText()
        val about = File(
            appDirectory,
            "src/main/res/layout/content_settings_about.xml",
        ).readText()
        val waiting = File(
            appDirectory,
            "src/main/res/layout/view_cast_commercial_waiting.xml",
        ).readText()

        assertTrue(activity.contains("settings_navigation_entitlement"))
        assertTrue(activity.contains("settings_navigation_about"))
        assertTrue(activity.contains("content_settings_commercial"))
        assertTrue(activity.contains("content_settings_about"))
        assertTrue(activity.contains("view_cast_commercial_waiting"))
        assertTrue(activity.contains("settings_title_text"))
        assertTrue(activity.contains("settings_entitlement_badge"))
        assertTrue(activity.contains("bg_commercial_corner_badge"))
        assertTrue(activity.contains("waiting_bottom_actions"))
        assertTrue(activity.contains("cast_commercial_waiting_pro_status"))
        assertTrue(activity.contains("cast_waiting_primary_translation_y"))
        assertTrue(commercial.contains("commercial_entitlement_page"))
        assertTrue(commercial.contains("commercial_order_page"))
        assertTrue(commercial.contains("commercial_qr_page"))
        assertTrue(about.contains("about_terms_qr"))
        assertTrue(waiting.contains("cast_commercial_purchase_ad"))
        assertTrue(waiting.contains("cast_commercial_waiting_purchase_detail"))
        assertTrue(waiting.contains("cast_commercial_buy_pro"))
        assertTrue(waiting.contains("cast_commercial_view_entitlement"))
        assertTrue(strings.contains("cast_commercial_waiting_trial"))
        assertTrue(strings.contains("cast_commercial_waiting_unavailable"))
        assertTrue(strings.contains("cast_commercial_waiting_expired_detail"))
        assertTrue(strings.contains("cast_commercial_waiting_pro"))
        assertTrue(strings.contains("cast_commercial_purchase_ad_prefix"))
        assertTrue(strings.contains("settings_navigation_about"))
    }

    @Test
    fun waitingStatesKeepPurchaseDetailBelowAdAndUseInlineProStatus() {
        val appDirectory = findAppDirectory()
        val activity = File(
            appDirectory,
            "src/main/res/layout/activity_main.xml",
        ).readText()
        val mainActivity = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/MainActivity.kt",
        ).readText()
        val waiting = File(
            appDirectory,
            "src/main/res/layout/view_cast_commercial_waiting.xml",
        ).readText()
        val renderer = File(
            appDirectory,
            "src/main/java/com/ninepointnine/desktopcast/CastCommercialWaitingRenderer.kt",
        ).readText()

        assertTrue(
            waiting.indexOf("cast_commercial_purchase_ad") <
                waiting.indexOf("cast_commercial_waiting_purchase_detail")
        )
        assertTrue(
            activity.indexOf("cast_commercial_waiting_pro_status") <
                activity.indexOf("settings_button")
        )
        assertTrue(
            activity.indexOf("cast_waiting_primary_translation_y") <
                activity.indexOf("waiting_icon")
        )
        assertTrue(
            activity.indexOf("view_cast_commercial_waiting") >
                activity.indexOf("waiting_title")
        )
        assertTrue(
            activity.indexOf("view_cast_commercial_waiting") <
                activity.indexOf("settings_button")
        )
        assertTrue(mainActivity.contains("View.INVISIBLE"))
        assertTrue(renderer.contains("ForegroundColorSpan"))
        assertTrue(renderer.contains("commercial_ad_original_price"))
        assertTrue(renderer.contains("ReplacementSpan"))
        assertTrue(renderer.contains("getTextBounds(\"权\""))
        assertTrue(mainActivity.contains("onBuyPro = ::openCommercialEntitlement"))
        assertTrue(mainActivity.contains("cast_commercial_waiting_unavailable"))
    }

    @Test
    fun runtimeBoundaryKeepsReceiverAliveWhenCommercialAccessIsDenied() {
        val router = File(
            findAppDirectory(),
            "src/main/java/com/ninepointnine/desktopcast/service/CastPlaybackRouter.kt",
        ).readText()
        val service = File(
            findAppDirectory(),
            "src/main/java/com/ninepointnine/desktopcast/service/CastService.kt",
        ).readText()

        assertTrue(router.contains("commercialAccess.hasCurrentAccess()"))
        assertTrue(router.contains("blockForCommercialAccess"))
        assertTrue(router.contains("coordinator.commercialAccessEnded()"))
        assertTrue(service.contains("playback.blockForCommercialAccess()"))
        assertTrue(service.contains("registerCommercialAccessBoundaryReceiver()"))
        assertTrue(service.contains("ACTION_SCREEN_ON"))
        assertTrue(service.contains("ACTION_TIME_CHANGED"))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
