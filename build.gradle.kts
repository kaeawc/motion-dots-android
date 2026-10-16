// Pin the Kotlin and Android Gradle plugin versions from the version catalog. AGP 9 ships
// built-in Kotlin support, so there is no separate kotlin-android plugin to apply; KGP on the
// buildscript classpath makes the Kotlin version match the compose-compiler plugin
// applied in :app.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.agp)
        classpath(libs.kgp)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
