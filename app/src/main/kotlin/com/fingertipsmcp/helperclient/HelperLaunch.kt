package com.fingertipsmcp.helperclient

/**
 * The one-line command that starts the helper as the shell UID and returns. The device shell
 * expands `pm path`; this process then detaches with `setsid`, so the command survives `adb shell`
 * exiting.
 */
object HelperLaunch {
    fun command(packageName: String): String = "adb shell '${deviceCommand(packageName)}'"

    fun deviceCommand(packageName: String): String =
        """
            CLASSPATH=${'$'}(pm path $packageName | tr -d "\r" | sed -e s/^package:// | tr "\n" :) app_process / com.fingertipsmcp.helper.Main --pkg $packageName
        """
            .trimIndent()
}
