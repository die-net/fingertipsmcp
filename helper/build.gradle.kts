plugins {
    id("phoneapi.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.die.phoneapi.helper"
    buildFeatures { aidl = true }
    // Tests build AccessibilityNodeInfo fakes, whose base class is android.jar's stub.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":api-model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
