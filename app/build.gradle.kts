import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing is opt-in. Credentials come from a gitignored
// keystore.properties for local builds, or RELEASE_* environment variables in
// CI. With neither present the release build stays unsigned, so forks and
// contributors without the key can still run :app:assembleRelease.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingSecret(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))?.takeIf { it.isNotBlank() }

val releaseStorePassword = signingSecret("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSecret("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSecret("keyPassword", "RELEASE_KEY_PASSWORD")
val releaseKeystore = signingSecret("storeFile", "RELEASE_STORE_FILE")
    ?.let { path -> File(path).takeIf(File::isAbsolute) ?: rootProject.file(path) }
    ?.takeIf(File::exists)
val canSignRelease = releaseKeystore != null &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "org.upscalerelay.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.upscalerelay.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.14.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 (JAR) signing is irrelevant at minSdk 29 and AGP ignores
                // it here. v3 carries the rotation-capable lineage.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Null when unconfigured, which leaves an unsigned APK rather than
            // silently falling back to the debug key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":relay-protocol"))
    implementation(project(":relay-client"))
    implementation(project(":player-mpv"))
    implementation(project(":relay-demux"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit)
}
