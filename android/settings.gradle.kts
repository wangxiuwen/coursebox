/*
 * Default to Google / Maven Central so CI (us-east) is reliable.
 * Chinese developers can layer Aliyun mirrors via an init script —
 *   echo 'allprojects { repositories {
 *     maven { url uri("https://maven.aliyun.com/repository/public") }
 *   } }' > ~/.gradle/init.d/aliyun-mirror.gradle
 * — without touching this file.
 */
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
rootProject.name = "coursebox-android"
include(":app")
