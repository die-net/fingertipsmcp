import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Component
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.report.ReportMergeTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

internal object Sdk {
    const val COMPILE = 37
    const val TARGET = 36
    const val MIN = 29
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            if (this is KotlinJvmCompilerOptions) {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

internal fun Project.configureDetekt() {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        allRules.set(true)
        // Parallel analysis shares the Kotlin compiler's KotlinCliJavaFileManagerImpl, which is not
        // thread-safe (KT-88461), so a run now and then fails with an
        // ArrayIndexOutOfBoundsException while analyzing some file. detekt/detekt#9121 is blocked
        // on that issue and recommends leaving parallel off. Both were still open for detekt
        // 2.0.0-alpha.6, so check them again before turning it on.
        parallel.set(false)
        debug.set(providers.gradleProperty("detektDebug").isPresent)
        config.setFrom(rootProject.file("config/detekt.yml"))
        basePath.set(rootProject.layout.projectDirectory)
    }
    val merge = rootProject.tasks.named("detektReportMerge", ReportMergeTask::class.java)
    tasks.withType<Detekt>().configureEach { finalizedBy(merge) }
    merge.configure { input.from(tasks.withType<Detekt>().map { it.reports.sarif.outputLocation }) }
    extensions.findByType(AndroidComponentsExtension::class.java)?.let { components ->
        val kotlinAndroid = extensions.getByType(KotlinAndroidExtension::class.java)
        components.onVariants { variant ->
            addJavacOutputToDetektClasspath(variant, kotlinAndroid)
            variant.nestedComponents.forEach { addJavacOutputToDetektClasspath(it, kotlinAndroid) }
        }
    }
}

/**
 * Detekt's Android classpath comes from the Kotlin compile task, so javac-compiled AIDL classes are
 * missing. `from()` must run after detekt sets its classpath convention, which it then extends; an
 * earlier `from()` or `setFrom()` replaces it. AGP creates the javac task after `onVariants`, so it
 * is looked up only when the detekt task is configured.
 */
private fun Project.addJavacOutputToDetektClasspath(
    component: Component,
    kotlinAndroid: KotlinAndroidExtension,
) {
    val javaCompileTaskName = component.computeTaskName("compile", "JavaWithJavac")
    kotlinAndroid.target.compilations
        .matching { it.name == component.name }
        .configureEach {
            tasks
                .named("detekt" + name.replaceFirstChar { it.uppercase() }, Detekt::class.java)
                .configure {
                    val javaCompile = tasks.named(javaCompileTaskName, JavaCompile::class.java)
                    classpath.from(javaCompile.flatMap { it.destinationDirectory })
                }
        }
}

internal fun Lint.configureLint(project: Project) {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = true
    lintConfig = project.rootProject.file("config/lint.xml")
}

internal val javaVersion = JavaVersion.VERSION_17

internal fun ApplicationExtension.configureAndroidApp(project: Project) {
    compileSdk = Sdk.COMPILE
    defaultConfig {
        minSdk = Sdk.MIN
        targetSdk = Sdk.TARGET
    }
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    lint { configureLint(project) }
}

internal fun LibraryExtension.configureAndroidLibrary(project: Project) {
    compileSdk = Sdk.COMPILE
    defaultConfig { minSdk = Sdk.MIN }
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    lint {
        configureLint(project)
        targetSdk = Sdk.TARGET
    }
}
