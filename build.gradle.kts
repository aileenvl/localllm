// ObjectBox publishes its Gradle plugin as a classpath JAR only (no plugin
// marker artifact on Gradle's plugin portal), so we wire it via the legacy
// buildscript block. The version is still single-sourced from libs.versions.toml.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.objectbox.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.androidxBaselineProfile) apply false
}
