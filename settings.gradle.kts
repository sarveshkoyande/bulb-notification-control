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
        // Thing/Tuya App SDK artifacts
        maven { setUrl("https://maven-other.tuya.com/repository/maven-releases/") }
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "BulbNotificationControl"
include(":app")
