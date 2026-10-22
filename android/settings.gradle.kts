pluginManagement {
    repositories {
        google()
        // Google's Maven Central mirror first: Maven Central rate-limits shared CI egress.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's Maven Central mirror first: Maven Central rate-limits shared CI egress.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "WristboundCompanion"
include(":app")
