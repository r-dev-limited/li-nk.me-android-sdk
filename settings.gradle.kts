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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "linkme-android"

include(":linkmekit")
project(":linkmekit").projectDir = file("linkmekit")

include(":example-app")
project(":example-app").projectDir = file("example-app")

