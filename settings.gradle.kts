pluginManagement {
    repositories {
        // Plugin markers are small; hit the canonical repos first so resolution is reliable on CI
        // (US runners, where the Aliyun mirrors are the flaky ones). The mirrors stay as a fallback
        // for the local China network.
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Mirrors first: Maven Central is flaky from this network.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "yins"
include(":app")
include(":testapp")
