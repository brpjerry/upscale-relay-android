plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.upscalerelay.demux"
    compileSdk = 37

    defaultConfig { minSdk = 29 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":relay-client"))
    testImplementation(libs.junit)
}
