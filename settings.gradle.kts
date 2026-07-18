pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
