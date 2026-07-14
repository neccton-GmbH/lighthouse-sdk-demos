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
        maven(url = "https://raw.githubusercontent.com/neccton-GmbH/lighthouse-sdk-android-dist/main/maven")
    }
}

rootProject.name = "LighthouseDemoApp"
include(":app")
