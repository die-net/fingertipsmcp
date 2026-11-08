package com.fingertipsmcp.core

/** One finished shell command. Output is truncated by the helper, not here. */
data class ShellResult(val exit: Int, val stdout: String = "", val stderr: String = "") {
    val ok: Boolean
        get() = exit == 0
}

/** A one-line reason for a failure, preferring stderr, for error messages and logs. */
fun ShellResult.failureMessage(): String =
    listOf(stderr, stdout)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.lineSequence()
        ?.first()
        ?.take(MAX_MESSAGE)
        .orEmpty()

private const val MAX_MESSAGE = 200
