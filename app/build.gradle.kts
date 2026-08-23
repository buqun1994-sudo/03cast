import java.io.File
import java.util.Properties

fun String.asBuildConfigString(): String = buildString {
    append('"')
    this@asBuildConfigString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

fun Properties.requiredValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Signing property '$name' is required")

fun Properties.requiredReleaseValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Release version property '$name' is required")

val releaseVersionPropertiesFile = rootProject.file("release-version.properties")
val releaseVersionProperties = Properties().apply {
    require(releaseVersionPropertiesFile.isFile) {
        "Release version properties file does not exist: $releaseVersionPropertiesFile"
    }
    releaseVersionPropertiesFile.inputStream().use(::load)
}
val releaseVersionName = releaseVersionProperties.requiredReleaseValue("releaseVersionName")
val releaseVersionCode = releaseVersionProperties.requiredReleaseValue("releaseVersionCode").toIntOrNull()
    ?: error("Release version property 'releaseVersionCode' must be a positive integer")
require(releaseVersionCode > 0) {
    "Release version property 'releaseVersionCode' must be a positive integer"
}
require(Regex("\\d+\\.\\d+\\.\\d+-icar03").matches(releaseVersionName)) {
    "Release version name must match <major>.<minor>.<patch>-icar03"
}

fun String.normalizedSha256OrNull(): String? = replace(":", "")
    .filterNot(Char::isWhitespace)
    .lowercase()
    .takeIf { value -> value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' } }

val deviceCommerceEnvironment = providers.gradleProperty("deviceCommerceEnvironment")
    .orElse("fixture")
    .get()
    .trim()
    .lowercase()
require(deviceCommerceEnvironment in setOf("fixture", "staging", "production")) {
    "deviceCommerceEnvironment must be fixture, staging or production"
}

val stagingCommerceApiBaseUrl = providers.gradleProperty("deviceCommerceStagingApiBaseUrl")
    .orElse("https://api-staging.9studio.fun")
    .get()
    .trim()
val stagingLicenseKeyId = providers.gradleProperty("deviceCommerceStagingLicenseKeyId")
    .orElse("")
    .get()
    .trim()
val stagingLicensePublicKeyBase64 = providers
    .gradleProperty("deviceCommerceStagingLicensePublicKeyBase64")
    .orElse("")
    .get()
    .trim()
val stagingSigningCertSha256 = providers.gradleProperty("deviceCommerceStagingSigningCertSha256")
    .orElse("")
    .get()
    .trim()
val productionCommerceApiBaseUrl = providers.gradleProperty("deviceCommerceProductionApiBaseUrl")
    .orElse("")
    .get()
    .trim()
val productionLicenseKeyId = providers.gradleProperty("deviceCommerceProductionLicenseKeyId")
    .orElse("")
    .get()
    .trim()
val productionLicensePublicKeyBase64 = providers
    .gradleProperty("deviceCommerceProductionLicensePublicKeyBase64")
    .orElse("")
    .get()
    .trim()
val productionSigningCertSha256 = providers.gradleProperty("deviceCommerceProductionSigningCertSha256")
    .orElse("")
    .get()
    .trim()

val productionCommerceConfigured = listOf(
    productionCommerceApiBaseUrl,
    productionLicenseKeyId,
    productionLicensePublicKeyBase64,
    productionSigningCertSha256,
).any(String::isNotBlank)
if (productionCommerceConfigured) {
    require(productionCommerceApiBaseUrl.startsWith("https://")) {
        "Production Device Commerce API must use HTTPS"
    }
    require(productionLicenseKeyId.isNotBlank()) {
        "Production Device Commerce license keyId is required"
    }
    require(productionLicensePublicKeyBase64.isNotBlank()) {
        "Production Device Commerce license public key is required"
    }
    require(productionSigningCertSha256.normalizedSha256OrNull() != null) {
        "Production APK signing certificate SHA-256 is required"
    }
}

val stagingSigningPropertiesFile = providers.gradleProperty("deviceCommerceStagingSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val stagingSigningProperties = Properties()
val stagingSigningStoreFile = if (deviceCommerceEnvironment == "staging") {
    require(stagingCommerceApiBaseUrl.startsWith("https://")) {
        "Staging Device Commerce API must use HTTPS"
    }
    require(stagingLicenseKeyId.isNotBlank()) {
        "Staging Device Commerce license keyId is required"
    }
    require(stagingLicensePublicKeyBase64.isNotBlank()) {
        "Staging Device Commerce license public key is required"
    }
    require(stagingSigningCertSha256.normalizedSha256OrNull() != null) {
        "Staging APK signing certificate SHA-256 is required"
    }
    val propertiesFile = requireNotNull(stagingSigningPropertiesFile) {
        "Staging APK signing properties file is required"
    }
    require(propertiesFile.isFile) {
        "Staging APK keystore properties file does not exist"
    }
    propertiesFile.inputStream().use(stagingSigningProperties::load)
    val configuredStoreFile = stagingSigningProperties.requiredValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Staging APK keystore does not exist" }
    resolved
} else {
    null
}

val productionSigningPropertiesFile = providers.gradleProperty("deviceCommerceProductionSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val productionSigningProperties = Properties()
val productionSigningStoreFile = if (deviceCommerceEnvironment == "production") {
    val propertiesFile = requireNotNull(productionSigningPropertiesFile) {
        "Production APK signing properties file is required"
    }
    require(propertiesFile.isFile) {
        "Production APK keystore properties file does not exist"
    }
    propertiesFile.inputStream().use(productionSigningProperties::load)
    val configuredStoreFile = productionSigningProperties.requiredValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) candidate else propertiesFile.parentFile.resolve(configuredStoreFile)
    require(resolved.isFile) { "Production APK keystore does not exist" }
    resolved
} else {
    null
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ninepointnine.desktopcast"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.ninepointnine.desktopcast"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (productionSigningStoreFile != null) {
            create("release") {
                storeFile = productionSigningStoreFile
                storePassword = productionSigningProperties.requiredValue("storePassword")
                keyAlias = productionSigningProperties.requiredValue("keyAlias")
                keyPassword = productionSigningProperties.requiredValue("keyPassword")
            }
        }
        if (stagingSigningStoreFile != null) {
            create("staging") {
                storeFile = stagingSigningStoreFile
                storePassword = stagingSigningProperties.requiredValue("storePassword")
                keyAlias = stagingSigningProperties.requiredValue("keyAlias")
                keyPassword = stagingSigningProperties.requiredValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (stagingSigningStoreFile != null) {
                signingConfigs.findByName("staging")?.let { signingConfig = it }
            }
            buildConfigField("String", "DEVICE_COMMERCE_ENVIRONMENT", deviceCommerceEnvironment.asBuildConfigString())
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_API_BASE_URL",
                (if (deviceCommerceEnvironment == "staging") stagingCommerceApiBaseUrl else "").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_KEY_ID",
                (if (deviceCommerceEnvironment == "staging") stagingLicenseKeyId else "").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64",
                (if (deviceCommerceEnvironment == "staging") stagingLicensePublicKeyBase64 else "").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_EXPECTED_SIGNING_CERT_SHA256",
                (if (deviceCommerceEnvironment == "staging") stagingSigningCertSha256 else "").asBuildConfigString(),
            )
            buildConfigField("String", "TERMS_ENVIRONMENT", "\"staging\"")
            buildConfigField(
                "String",
                "TERMS_URL",
                "\"https://staging.9studio.fun/icar03/terms\"",
            )
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "TERMS_ENVIRONMENT", "\"production\"")
            buildConfigField(
                "String",
                "TERMS_URL",
                "\"https://9.9studio.fun/icar03/terms\"",
            )
            buildConfigField("String", "DEVICE_COMMERCE_ENVIRONMENT", "\"production\"")
            buildConfigField("String", "DEVICE_COMMERCE_API_BASE_URL", productionCommerceApiBaseUrl.asBuildConfigString())
            buildConfigField("String", "DEVICE_COMMERCE_LICENSE_KEY_ID", productionLicenseKeyId.asBuildConfigString())
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64",
                productionLicensePublicKeyBase64.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_EXPECTED_SIGNING_CERT_SHA256",
                productionSigningCertSha256.asBuildConfigString(),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        prefab = true
        viewBinding = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(releaseVersionName)
            output.versionCode.set(releaseVersionCode)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0-beta01")
    implementation("androidx.media3:media3-ui:1.11.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
