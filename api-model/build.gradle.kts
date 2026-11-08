plugins {
    id("fingertips.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin { explicitApi() }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
