plugins {
    id("phoneapi.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.die.phoneapi.helper"
    buildFeatures { aidl = true }
}

dependencies {
    api(project(":api-model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
