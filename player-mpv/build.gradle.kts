plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.upscalerelay.player.mpv"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

val verifyMpvNative by tasks.registering {
    val nativeDirectory = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    inputs.dir(nativeDirectory).optional()
    doLast {
        val missing = listOf("libmpv.so", "libplayer.so")
            .filterNot { nativeDirectory.file(it).asFile.isFile }
        check(missing.isEmpty()) {
            "Missing ${missing.joinToString()} in ${nativeDirectory.asFile}. " +
                "Run android_client/native/fetch_mpv_native.ps1 (Windows) or " +
                "android_client/native/fetch_mpv_native.sh first."
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyMpvNative) }
