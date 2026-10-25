pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// A toolchain repository for jvmToolchain(17). Without one, Gradle 10 refuses
// a JDK it provisioned itself, and a machine with no JDK 17 cannot build.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "video-upscale-relay-android"

include(":app")
include(":relay-protocol")
include(":relay-client")
include(":player-mpv")
include(":relay-demux")
