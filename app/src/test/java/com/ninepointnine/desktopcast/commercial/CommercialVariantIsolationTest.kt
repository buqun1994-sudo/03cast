package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommercialVariantIsolationTest {
    @Test
    fun fixtureCatalogMatches03castCloudPricing() {
        val staging = DebugCommercialFixtureCatalog.staging
        assertEquals(2, staging.listAmount)
        assertEquals(5_000, staging.paymentRatioBps)
        assertEquals(1, staging.calculatedAmount)
        assertEquals(1, staging.finalAmount)

        val production = DebugCommercialFixtureCatalog.production
        assertEquals(6_500, production.listAmount)
        assertEquals(6_000, production.paymentRatioBps)
        assertEquals(3_900, production.calculatedAmount)
        assertEquals(3_900, production.finalAmount)

        val zeroRatio = DebugCommercialFixtureCatalog.zeroRatio
        assertEquals(6_500, zeroRatio.listAmount)
        assertEquals(0, zeroRatio.calculatedAmount)
        assertEquals(1, zeroRatio.finalAmount)
        assertTrue(zeroRatio.minimumChargeApplied)
    }

    @Test
    fun productContractIsolatedFrom03lyrics() {
        assertEquals("03cast", DeviceCommerceProductContract.PRODUCT_ID)
        assertEquals("03cast_pro_device_cny", DeviceCommerceProductContract.SKU)
        assertEquals("com.ninepointnine.desktopcast", DeviceCommerceProductContract.PACKAGE_NAME)
        assertFalse(DeviceCommerceProductContract.PRODUCT_ID == "03lyrics")
        assertFalse(DeviceCommerceProductContract.SKU.contains("03lyrics"))
    }

    @Test
    fun fixtureOnlyAmountsAndQrMarkersDoNotReachMainOrRelease() {
        val appDirectory = findAppDirectory()
        val productionText = sequenceOf(
            File(appDirectory, "src/main"),
            File(appDirectory, "src/release"),
        ).flatMap { root -> root.walkTopDown().filter(File::isFile) }
            .filter { it.extension in setOf("kt", "xml", "kts") }
            .joinToString("\n", transform = File::readText)

        listOf(
            "¥0.02",
            "¥0.01",
            "¥65.00",
            "¥39.00",
            "icar 03",
            "debug-fixture-key-v2",
            "fixture.03cast.invalid",
        ).forEach { marker -> assertFalse(marker, productionText.contains(marker)) }
        assertTrue(
            File(appDirectory, "src/debug").walkTopDown()
                .filter(File::isFile)
                .any { it.readText().contains("fixture.03cast.invalid") }
        )
    }

    @Test
    fun releaseBuildKeepsOptimizationEnabled() {
        val buildScript = File(findAppDirectory(), "build.gradle.kts").readText()
        assertTrue(buildScript.contains("isMinifyEnabled = true"))
        assertTrue(buildScript.contains("isShrinkResources = true"))
        assertTrue(buildScript.contains("proguard-android-optimize.txt"))
    }

    @Test
    fun packageMigrationKeepsNativeJniAndSigningInputsOnNewIdentity() {
        val appDirectory = findAppDirectory()
        val buildScript = File(appDirectory, "build.gradle.kts").readText()
        val nativeBridge = File(appDirectory, "src/main/cpp/native_bridge.cpp").readText()

        assertTrue(buildScript.contains("namespace = \"com.ninepointnine.desktopcast\""))
        assertTrue(buildScript.contains("applicationId = \"com.ninepointnine.desktopcast\""))
        assertTrue(buildScript.contains("deviceCommerceProductionSigningPropertiesFile"))
        assertFalse(buildScript.contains("rootProject.file(\"keystore.properties\")"))
        assertTrue(
            nativeBridge.contains(
                "Java_com_ninepointnine_desktopcast_bridge_NativeBridge_nativeInit",
            )
        )
        assertFalse(nativeBridge.contains("Java_com_" + "tcrrry_desktopcast"))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
