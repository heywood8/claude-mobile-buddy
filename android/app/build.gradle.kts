import java.util.Properties

/**
 * Signing material lives outside the repository, in ~/.config/claude-mobile-buddy, next to the
 * bridge's own identity. Nothing here reads a password from the project tree, so there is no
 * file anyone can commit by accident.
 *
 * Without it the release build is simply unsigned: CI can still compile and check, it just
 * cannot produce something installable.
 */
val signingConfigFile = File(System.getProperty("user.home"), ".config/claude-mobile-buddy/signing.properties")
val signingProps = Properties().apply {
    if (signingConfigFile.isFile) signingConfigFile.inputStream().use(::load)
    // CI has no home directory worth writing to and no business keeping one. Environment
    // wins where it is set, so the same build works from a laptop and from a runner.
    System.getenv("CMB_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("CMB_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("CMB_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("CMB_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.heywood8.claudebuddy"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.heywood8.claudebuddy"
        // BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT are runtime permissions from API 31.
        // Below that they would need the legacy permission model, and this app has no
        // reason to run there.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.7.1" // x-release-please-version
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = File(
                    signingProps.getProperty("storeFile")
                        ?: File(signingConfigFile.parentFile, "release.jks").path
                )
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias") ?: "claude-buddy"
                keyPassword = signingProps.getProperty("keyPassword")
                    ?: signingProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable, which is the whole point: the app holds a key that authorises
            // running commands on a workstation, and a debuggable app hands its own identity
            // to anyone with adb access.
            isDebuggable = false
            // Both things R8 could break here fail silently rather than loudly — a snapshot
            // that no longer decodes, a camera that looks at a valid code and does nothing —
            // so proguard-rules.pro keeps the serializers and the whole ZXing surface rather
            // than trusting the analysis.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // ZXing core only — the decoder, no Play Services, no bundled UI. Pairing must not depend
    // on Google Play being present or up to date.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
