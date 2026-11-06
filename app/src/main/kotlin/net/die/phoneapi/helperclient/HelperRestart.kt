package net.die.phoneapi.helperclient

/** Delay before the next automatic helper restart. Grows from 1s and stops at 30s. */
internal fun restartDelayMs(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 5)
    return (1_000L shl shift).coerceAtMost(30_000L)
}

/** An adbd TLS port from the adb service. Zero and below mean it has none. */
internal fun validAdbPort(port: Int): Int? = port.takeIf { it in 1..65_535 }
