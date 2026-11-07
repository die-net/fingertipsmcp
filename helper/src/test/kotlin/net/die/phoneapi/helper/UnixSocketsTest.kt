package net.die.phoneapi.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnixSocketsTest {
    @Test
    fun `reads devtools sockets`() {
        val table =
            listOf(
                    "Num       RefCount Protocol Flags    Type St Inode Path",
                    "00000000: 00000002 00000000 00010000 0001 01 100 @chrome_devtools_remote",
                    "00000000: 00000002 00000000 00010000 0001 01 200 @webview_devtools_remote_44",
                    "00000000: 00000002 00000000 00010000 0001 01 300 @not_devtools",
                )
                .joinToString("\n")
        val sockets = parseDevtoolsSockets(table)
        assertEquals(
            listOf("chrome_devtools_remote", "webview_devtools_remote_44"),
            sockets.map { it.name },
        )
        assertEquals(listOf(100L, 200L), sockets.map { it.inode })
    }

    /**
     * Rows from an Android 17 emulator. Each connection to a listener has a row under the
     * listener's name: state 03 once accepted, and state 02 while it waits in the queue of a
     * process that is frozen, where it stays after the client hangs up. Counted as sockets, each
     * listing connected once per row and so added as many rows again.
     */
    @Test
    fun `a connection is not a socket`() {
        val table =
            listOf(
                    "Num       RefCount Protocol Flags    Type St Inode Path",
                    "00000000: 00000002 00000000 00010000 0001 01 20410 @chrome_devtools_remote",
                    "00000000: 00000003 00000000 00000000 0001 03 83100 @chrome_devtools_remote",
                    "00000000: 00000003 00000000 00000000 0001 03 83101 @chrome_devtools_remote",
                    "00000000: 00000002 00000000 00010000 0001 01 23428 @webview_devtools_remote_9",
                    "00000000: 00000002 00000000 00000000 0001 02     0 @webview_devtools_remote_9",
                    "00000000: 00000002 00000000 00000000 0001 02     0 @webview_devtools_remote_9",
                )
                .joinToString("\n")
        assertEquals(
            listOf(
                AbstractSocket("chrome_devtools_remote", 20_410L),
                AbstractSocket("webview_devtools_remote_9", 23_428L),
            ),
            parseDevtoolsSockets(table),
        )
    }
}
