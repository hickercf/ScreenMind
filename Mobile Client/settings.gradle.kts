pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (file(".tools/maven").exists()) maven {
            url = uri(".tools/maven")
            content { includeModule("com.microsoft.onnxruntime", "onnxruntime-android") }
        }
        google(); mavenCentral()
    }
}
rootProject.name = "Weiguang"
include(":app")
include(":chronocore")
project(":chronocore").projectDir = file("../ChronoCapsule/core")
