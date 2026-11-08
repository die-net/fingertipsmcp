package com.fingertipsmcp.helperclient

import com.flyfishxu.kadb.exception.AdbPairAuthException
import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pairing tries loopback, then the LAN address, as pairWith does. */
class PairHostsTest {
    private val hosts = listOf("127.0.0.1", "192.168.1.20")
    private val tried = ArrayList<String>()

    @Test
    fun `a wrong code is sent once`() = runTest {
        assertThrows<AdbPairAuthException> {
            pairAtFirstListening(hosts) { host ->
                tried += host
                throw AdbPairAuthException()
            }
        }
        assertEquals(listOf("127.0.0.1"), tried)
    }

    @Test
    fun `refused loopback tries LAN`() = runTest {
        pairAtFirstListening(hosts) { host ->
            tried += host
            if (host == "127.0.0.1") throw ConnectException("Connection refused")
        }
        assertEquals(hosts, tried)
    }

    @Test
    fun `refused everywhere throws`() = runTest {
        assertThrows<ConnectException> {
            pairAtFirstListening(hosts) { host ->
                tried += host
                throw ConnectException("Connection refused")
            }
        }
        assertEquals(hosts, tried)
    }
}
