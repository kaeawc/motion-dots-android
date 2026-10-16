import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val javaTarget = libs.versions.build.java.target.get().toInt()

// Pin compilation to a Java toolchain so builds behave identically regardless of the JDK that
// happens to launch Gradle. AGP registers the Java extension for Android modules, and KGP picks
// up the same toolchain. (This is what the template's androidbuild.kotlin-common convention did.)
plugins.withType<JavaBasePlugin>().configureEach {
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(javaTarget)) }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        languageVersion.set(KotlinVersion.fromVersion(libs.versions.build.kotlin.language.get()))
        jvmTarget.set(JvmTarget.fromTarget(javaTarget.toString()))
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

android {
    namespace = "dev.jasonpearson.motiondots"
    compileSdk = libs.versions.build.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.jasonpearson.motiondots"
        minSdk = libs.versions.build.android.minSdk.get().toInt()
        targetSdk = libs.versions.build.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        named("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Personal sideloaded app: R8 is off to avoid shrinker surprises with
            // kotlinx.serialization / OkHttp reflection and to keep the build simple. To turn it
            // on, set isMinifyEnabled/isShrinkResources to true; proguard-rules.pro already has
            // the kotlinx.serialization rules.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No release keystore exists, so sign with the debug key to keep the APK installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(javaTarget)
        targetCompatibility = JavaVersion.toVersion(javaTarget)
    }

    buildFeatures {
        compose = true
        // Compose only.
        viewBinding = false
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        sarifOutput = file("${layout.buildDirectory.get()}/reports/lint-results.sarif")
        htmlOutput = file("${layout.buildDirectory.get()}/reports/lint-results.html")
        abortOnError = true
        checkDependencies = true
        ignoreTestSources = true

        // Select a config from ../android-lint with -Plint-config=<name> (default: "default").
        val customLintConfig = project.findProperty("lint-config") as? String ?: "default"

        val lintConfigFile = file("../android-lint/${customLintConfig}-lint.xml")
        if (!lintConfigFile.exists()) {
            throw GradleException("Lint config file not found: ${lintConfigFile.absolutePath}")
        } else {
            println("Using ${lintConfigFile.absolutePath} for lint config")
        }

        lintConfig = lintConfigFile
    }
}

dependencies {
    // Needed for reading Java 19+ class files due to JVM target higher than 11
    implementation(platform(libs.asm.bom))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material)
    implementation(libs.compose.ui.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
}

val gradleWorkerJvmArgs = providers.gradleProperty("org.gradle.testWorker.jvmargs")

tasks.withType<Test>().configureEach {
    gradleWorkerJvmArgs.orNull?.let { args -> jvmArgs(args.split(" ").filter { it.isNotBlank() }) }
}
