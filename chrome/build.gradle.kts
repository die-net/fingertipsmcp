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

/**
 * Concatenates the `META-INF/services` files of every runtime jar, so the fat jar keeps each
 * provider instead of the first jar's.
 */
abstract class MergeServices : DefaultTask() {
    @get:InputFiles @get:Classpath abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory abstract val output: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun merge() {
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        jars
            .filter { it.isFile }
            .forEach { jar ->
                archives
                    .zipTree(jar)
                    .matching { include("META-INF/services/*") }
                    .visit {
                        if (!isDirectory) {
                            val lines =
                                file.readLines().map(String::trim).filter {
                                    it.isNotEmpty() && !it.startsWith("#")
                                }
                            merged.getOrPut(name) { LinkedHashSet() } += lines
                        }
                    }
            }
        val dir = output.get().asFile.resolve("META-INF/services")
        dir.deleteRecursively()
        dir.mkdirs()
        merged.forEach { (name, lines) ->
            dir.resolve(name).writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}

val mergeServices =
    tasks.register<MergeServices>("mergeServices") {
        jars.from(configurations.runtimeClasspath)
        output.set(layout.buildDirectory.dir("merged-services"))
    }

val runtimeJars = configurations.runtimeClasspath

/**
 * `build/dist/fingertipsmcp-chrome.jar`: the app and every runtime dependency, run with `java
 * -jar`.
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
                "Multi-Release" to "true",
                // JNA loads its native library to start Chrome on Windows. Java 24 and later warn
                // on stderr when code outside a named module does that, unless this allows it.
                // Java 21 ignores it.
                "Enable-Native-Access" to "ALL-UNNAMED",
            )
        }
        from(mergeServices)
        from(sourceSets.main.map { it.output })
        from(
            runtimeJars.map { classpath ->
                classpath.map { if (it.isDirectory) it else zipTree(it) }
            }
        ) {
            exclude(
                "META-INF/services/**",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.EC",
                "META-INF/INDEX.LIST",
                "META-INF/MANIFEST.MF",
                "module-info.class",
                "META-INF/versions/*/module-info.class",
            )
        }
    }

tasks.named("assemble") { dependsOn(fatJar) }

tasks.named<Jar>("jar") {
    manifest { attributes("Implementation-Version" to project.version) }
}
