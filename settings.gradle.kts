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
        // Thing/Tuya App SDK artifacts are NOT on Maven Central.
        maven { setUrl("https://maven-other.tuya.com/repository/maven-releases/") }
        maven { setUrl("https://maven-other.tuya.com/repository/maven-commercial-releases/") }
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "BulbNotificationControl"
include(":app")
