# R8 rules for fingertipsmcp-chrome.jar. The libraries' own rules come from their jars, as they do
# for the release APK; these cover what only the desktop jar does.

# Names stay as written, so stack traces in logs read without a mapping file.
-dontobfuscate

# Ktor and kotlinx-serialization read generic signatures and annotations by reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

-keep class com.fingertipsmcp.chrome.MainKt { public static void main(java.lang.String[]); }

# JNA maps Win32 functions onto Library interfaces through proxies, and struct fields by name.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.PointerType { *; }
-keep class * implements com.sun.jna.Callback { *; }

# slf4j finds slf4j-simple through ServiceLoader.
-keep class org.slf4j.spi.SLF4JServiceProvider
-keep class org.slf4j.simple.SimpleServiceProvider { <init>(); }

# kotlin-logging, which the MCP SDK uses, has a Logback backend it picks only when Logback is on
# the classpath. The jar logs through slf4j-simple, so Logback is absent.
-dontwarn ch.qos.logback.**
