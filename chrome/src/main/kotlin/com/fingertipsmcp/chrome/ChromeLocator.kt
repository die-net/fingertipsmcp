package com.fingertipsmcp.chrome

import java.io.File

/** A browser to run. [chromium] is true for anything that is not Google Chrome. */
data class BrowserBinary(val path: File, val chromium: Boolean)

/**
 * Finds Google Chrome stable where its installers put it, then Chromium. An explicit path, from
 * `--chrome` or `FINGERTIPS_CHROME`, wins.
 */
class ChromeLocator(
    private val env: Map<String, String> = System.getenv(),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val home: File = File(System.getProperty("user.home")),
    private val runnable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    fun find(explicit: String?): BrowserBinary {
        val chosen = explicit ?: env["FINGERTIPS_CHROME"]?.takeIf { it.isNotBlank() }
        if (chosen != null) {
            val file = resolve(chosen) ?: throw LaunchException("No browser at $chosen")
            return BrowserBinary(file, chromium = !looksLikeChrome(file))
        }
        chromeCandidates().firstOrNull(runnable)?.let {
            return BrowserBinary(it, chromium = false)
        }
        chromiumCandidates().firstOrNull(runnable)?.let {
            return BrowserBinary(it, chromium = true)
        }
        throw LaunchException(
            "Google Chrome is not installed where fingertips-chrome looks. Install Chrome, or pass --chrome " +
                "with the path to Chrome or Chromium."
        )
    }

    private fun resolve(name: String): File? {
        val file = File(name)
        if (file.isAbsolute || name.contains(File.separatorChar)) return file.takeIf(runnable)
        return onPath(name)
    }

    private fun chromeCandidates(): List<File> =
        if (isMac(osName)) {
            listOf(
                File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                File(home, "Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
            )
        } else {
            listOfNotNull(onPath("google-chrome-stable"), onPath("google-chrome")) +
                listOf(
                    File("/opt/google/chrome/chrome"),
                    File("/usr/bin/google-chrome-stable"),
                    File("/usr/bin/google-chrome"),
                )
        }

    private fun chromiumCandidates(): List<File> =
        if (isMac(osName)) {
            listOf(
                File("/Applications/Chromium.app/Contents/MacOS/Chromium"),
                File(home, "Applications/Chromium.app/Contents/MacOS/Chromium"),
            )
        } else {
            listOfNotNull(onPath("chromium"), onPath("chromium-browser")) +
                listOf(
                    File("/usr/bin/chromium"),
                    File("/usr/bin/chromium-browser"),
                    File("/snap/bin/chromium"),
                )
        }

    private fun onPath(name: String): File? =
        env["PATH"]
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotEmpty)
            .map { File(it, name) }
            .firstOrNull(runnable)

    private fun looksLikeChrome(file: File): Boolean {
        val path = file.path.lowercase()
        return "chromium" !in path && ("google" in path || "chrome" in file.name.lowercase())
    }
}

/** The browser could not be found or started. */
class LaunchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What Chromium costs in stealth, logged when it stands in for Chrome. */
const val CHROMIUM_WARNING: String =
    "Google Chrome was not found, so fingertips-chrome is using Chromium. Sites can tell: Client Hints " +
        "(Sec-CH-UA) name Chromium instead of Google Chrome, and Chromium lacks Chrome's " +
        "proprietary codecs (H.264, AAC) and Widevine. Install Google Chrome, or pass --chrome."
