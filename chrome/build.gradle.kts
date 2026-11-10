plugins {
    id("fingertips.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    application
}

val tagVersion: String? = providers.environmentVariable("FINGERTIPS_VERSION").orNull

version = tagVersion?.removePrefix("v") ?: "0.0.0-dev"

application {
    mainClass.set("com.fingertipsmcp.chrome.MainKt")
    applicationName = "fingertips-chrome"
}

dependencies {
    implementation(project(":api-model"))
    implementation(project(":browser"))
    implementation(project(":server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.io.core)
    implementation(libs.jna)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.mcp.server)
    implementation(libs.mcp.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(testFixtures(project(":browser")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val runtimeJars = configurations.runtimeClasspath

/** R8's command line for [shrunkClasses], with what it reads and writes declared to Gradle. */
abstract class R8Arguments : CommandLineArgumentProvider {
    /** The app's jar and every runtime dependency, each passed to R8 as its own input. */
    @get:Classpath abstract val program: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val rules: RegularFileProperty

    /** The JDK R8 resolves the platform's classes against. */
    @get:Input abstract val jdk: Property<String>

    @get:OutputFile abstract val output: RegularFileProperty

    override fun asArguments(): List<String> =
        listOf(
            "--release",
            "--classfile",
            "--output",
            output.get().asFile.path,
            "--lib",
            jdk.get(),
            "--pg-conf",
            rules.get().asFile.path,
        ) + program.files.map { it.path }
}

/** R8, run on its own to shrink the jar as it shrinks the release APK. */
val r8: Configuration = configurations.create("r8")

dependencies { r8(libs.r8) }

/** The JDK whose classes R8 treats as the library the jar runs against. */
val libraryJdk =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath }

/**
 * The app's classes and every runtime dependency's, less what nothing reaches, with their
 * resources. Each jar goes to R8 on its own, as AGP passes them for the APK, so R8 applies the
 * rules each library carries: given one merged jar, it reads only the `META-INF/com.android.tools`
 * rules and skips `META-INF/proguard`, where Ktor keeps its own. `proguard-rules.pro` adds what
 * only this jar needs. R8 merges the `META-INF/services` files, and drops one whose interface it
 * removed, since nothing can then load it.
 */
val shrunkClasses =
    tasks.register<JavaExec>("shrunkClasses") {
        classpath(r8)
        mainClass.set("com.android.tools.r8.R8")
        argumentProviders.add(
            objects.newInstance<R8Arguments>().apply {
                program.from(tasks.named<Jar>("jar").flatMap { it.archiveFile }, runtimeJars)
                rules.set(layout.projectDirectory.file("proguard-rules.pro"))
                jdk.set(libraryJdk.map { it.asFile.path })
                output.set(layout.buildDirectory.file("shrunk/classes.jar"))
            }
        )
    }

/**
 * `build/dist/fingertipsmcp-chrome.jar`: the app and every runtime dependency, shrunk, run with
 * `java -jar`. Of JNA's native libraries it keeps only those for 64-bit Windows, since only Windows
 * uses JNA.
 */
val fatJar =
    tasks.register<Jar>("fatJar") {
        group = "distribution"
        description = "Builds fingertipsmcp-chrome.jar with its dependencies."
        archiveFileName.set("fingertipsmcp-chrome.jar")
        destinationDirectory.set(layout.buildDirectory.dir("dist"))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes(
                "Main-Class" to "com.fingertipsmcp.chrome.MainKt",
                "Implementation-Title" to "FingertipsMCP for Chrome",
                "Implementation-Version" to project.version,
                // JNA loads its native library to start Chrome on Windows. Java 24 and later warn
                // on stderr when code outside a named module does that, unless this allows it.
                // Java 21 ignores it.
                "Enable-Native-Access" to "ALL-UNNAMED",
            )
        }
        from(shrunkClasses.map { zipTree(it.outputs.files.singleFile) }) {
            exclude(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.EC",
                "META-INF/INDEX.LIST",
                "META-INF/MANIFEST.MF",
                "META-INF/proguard/**",
                "META-INF/com.android.tools/**",
                "module-info.class",
                "META-INF/versions/**",
                "com/sun/jna/aix-*/**",
                "com/sun/jna/darwin-*/**",
                "com/sun/jna/dragonflybsd-*/**",
                "com/sun/jna/freebsd-*/**",
                "com/sun/jna/linux-*/**",
                "com/sun/jna/openbsd-*/**",
                "com/sun/jna/sunos-*/**",
                "com/sun/jna/win32-x86/**",
            )
        }
    }

tasks.named("assemble") { dependsOn(fatJar) }

tasks.named<Jar>("jar") {
    manifest { attributes("Implementation-Version" to project.version) }
}
