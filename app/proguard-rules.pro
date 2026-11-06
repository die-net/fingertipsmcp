# The abstract-socket engine constructs Ktor's internal CIO call by reflection.
-keep class io.ktor.server.cio.CIOApplicationCall { *; }

# The helper is started by app_process via reflection on its main class.
-keep class net.die.phoneapi.helper.Main { public static void main(java.lang.String[]); }
# The helper's copy of a hidden framework interface, which the framework's own class replaces.
-keep class android.content.IOnPrimaryClipChangedListener** { *; }

# Ktor's IntellijIdeaDebugDetector asks java.lang.management, which Android lacks, whether a JDWP
# agent is attached. It catches the Throwable and answers false, so the reference is harmless.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
