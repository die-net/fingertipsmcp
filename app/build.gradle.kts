plugins {
    id("fingertips.android.application")
    alias(libs.plugins.kotlin.serialization)
}

val tagVersion: String? = providers.environmentVariable("FINGERTIPS_VERSION").orNull

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) =
        version.removePrefix("v").substringBefore('-').split('.').map(String::toInt).let {
            Triple(it[0], it.getOrElse(1) { 0 }, it.getOrElse(2) { 0 })
        }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.fingertipsmcp"

    defaultConfig {
        applicationId = providers.gradleProperty("fingertips.applicationId").get()
        versionName = tagVersion?.removePrefix("v") ?: "0.0.0-dev"
        versionCode = tagVersion?.let(::versionCodeOf) ?: 1
    }

    signingConfigs {
        create("release") {
            val storePath = providers.environmentVariable("FINGERTIPS_KEYSTORE_PATH").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("FINGERTIPS_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FINGERTIPS_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("FINGERTIPS_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (providers.environmentVariable("FINGERTIPS_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/DEPENDENCIES",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/license/**",
                )
        }
    }
}

dependencies {
    constraints {
        // activity 1.13 still requests core-ktx 1.18. Those Kotlin extensions now live in core
        // 1.19, so the older jar duplicates them.
        implementation(libs.androidx.core.ktx)
    }
    implementation(project(":api-model"))
    implementation(project(":browser"))
    implementation(project(":server"))
    implementation(libs.slf4j.api)
    implementation(project(":helper"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.io)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.events)
    implementation(libs.ktor.http.cio)
    implementation(libs.jspecify)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.websockets)
    implementation(libs.ktor.serialization.json)

    implementation(libs.kadb)
    // AdbStream.source and AdbStream.sink are Okio types.
    implementation(libs.okio)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
