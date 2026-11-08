pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.mozilla.org/maven2/") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Summit"
include(":app")

include(":gecko-prototype")
