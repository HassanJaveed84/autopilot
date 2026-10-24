plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// One stable signing key for personal builds, so each new APK installs over the previous one and
// keeps its data (Android refuses an update signed with a different key, and every CI runner would
// otherwise mint a fresh debug key). The key is never committed: CI restores it from the
// AUTOPILOT_KEYSTORE_B64 secret; locally put it at keystore/personal.jks (or set AUTOPILOT_KEYSTORE)
// and give the password via AUTOPILOT_KEYSTORE_PASSWORD or autopilot.keystorePassword in
// ~/.gradle/gradle.properties. Without it, builds fall back to the Android debug key.
val personalKeystore = file(System.getenv("AUTOPILOT_KEYSTORE") ?: "$rootDir/keystore/personal.jks")
val personalKeystorePassword: String? =
    System.getenv("AUTOPILOT_KEYSTORE_PASSWORD") ?: providers.gradleProperty("autopilot.keystorePassword").orNull
val hasPersonalKey = personalKeystore.isFile && !personalKeystorePassword.isNullOrBlank()

android {
    namespace = "com.lifeadmin.autopilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeadmin.autopilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasPersonalKey) {
            create("personal") {
                storeFile = personalKeystore
                storePassword = personalKeystorePassword
                keyAlias = "autopilot"
                keyPassword = personalKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (hasPersonalKey) signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal-use builds: the personal key when configured (see above), else the debug key.
            // Not for store distribution — never commit signing keys.
            signingConfig = signingConfigs.getByName(if (hasPersonalKey) "personal" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
            all { test ->
                test.systemProperty("robolectric.logging", "stderr")
                // Optional mirror for Robolectric's android-all jars (set in ~/.gradle/gradle.properties).
                providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
                test.maxHeapSize = "2g"
                // Development-only paid evaluation (src/test/.../liveeval): never part of a normal or CI run.
                if (providers.gradleProperty("liveEval").orNull == "true") {
                    test.filter.includeTestsMatching("com.lifeadmin.autopilot.liveeval.*")
                    test.systemProperty("liveEval.enabled", "true")
                    test.systemProperty("liveEval.outDir", layout.buildDirectory.dir("live-eval").get().asFile.absolutePath)
                    providers.gradleProperty("liveEvalRerun").orNull?.let { test.systemProperty("liveEval.rerun", it) }
                    // Which scenario: R-N-a (the default), R-A, or R-N-b (clarification only, no paid call).
                    providers.gradleProperty("liveEvalScenario").orNull?.let { test.systemProperty("liveEval.scenario", it) }
                    // Experiment: A/B scenario and engine for LiveAbEval (m4, aifirst, or both when absent).
                    providers.gradleProperty("liveEvalAb").orNull?.let { test.systemProperty("liveEval.ab", it) }
                    providers.gradleProperty("liveEvalEngine").orNull?.let { test.systemProperty("liveEval.engine", it) }
                    test.outputs.upToDateWhen { false }
                } else {
                    test.exclude("**/liveeval/**")
                }
            }
        }
    }

    sourceSets {
        // Exported Room schemas are needed by migration tests (instrumented and Robolectric).
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDir("$projectDir/schemas")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // Dependency/SDK "newer version available" checks require network lookups and
        // are informational; versions are pinned deliberately (see docs/ARCHITECTURE.md).
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Optional AI planning (M2). Only the ai/anthropic package may import com.anthropic.*.
    implementation(libs.anthropic.java)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
