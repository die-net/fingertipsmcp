plugins {
    id("fingertips.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    // Fakes of browser types, such as TabBrowser, that other modules' tests use too.
    `java-test-fixtures`
}

// PageScriptsTest runs the page scripts' tests under node, reading the scripts and their tests from
// these directories, which are not on the test classpath.
tasks.withType<Test>().configureEach {
    inputs
        .dir("src/main/resources/com/fingertipsmcp/browser")
        .withPropertyName("pageScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir("src/test/js")
        .withPropertyName("pageScriptTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    api(project(":api-model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.ktor.io)
    implementation(libs.ktor.websockets)
    implementation(libs.slf4j.api)

    testFixturesApi(project(":api-model"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
