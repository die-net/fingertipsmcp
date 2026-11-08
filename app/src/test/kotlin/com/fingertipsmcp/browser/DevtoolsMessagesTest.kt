package com.fingertipsmcp.browser

import com.flyfishxu.kadb.exception.AdbAuthException
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevtoolsMessagesTest {
    @Test
    fun `android 10 names both tunnels`() {
        val message = usbTunnelMessage(CHROME_SOCKET)
        assertTrue(message.contains("fingertips helper"))
        assertTrue(message.contains(CHROME_SOCKET))
        assertTrue(message.contains("fingertips_cdp"))
        assertTrue(message.contains("9222"))
    }

    @Test
    fun `auth rejection names pairing`() {
        val message = devtoolsConnectMessage("127.0.0.1", AdbAuthException())
        assertTrue(message.contains("Pair again"))
        assertTrue(message.contains("debugging key"))
    }

    @Test
    fun `down port names options`() {
        val message = devtoolsConnectMessage("127.0.0.1", IOException("Connection refused"))
        assertTrue(message.contains("Developer options"))
        assertTrue(message.contains("wireless debugging"))
    }

    @Test
    fun `chrome socket names usb`() {
        val message = devtoolsConnectMessage(CHROME_SOCKET, IOException("closed"))
        assertTrue(message.contains("USB debugging"))
        assertTrue(message.contains("running"))
    }

    @Test
    fun `names chrome and usb`() {
        val message =
            browserGapMessage(BrowserGap.NONE, chromeInstalled = false, usbDebugging = false)
        assertTrue(message.contains("Chrome is not installed"))
        assertTrue(message.contains("USB debugging is off"))
        assertTrue(message.contains("setWebContentsDebuggingEnabled"))
    }

    @Test
    fun `missing chrome vs idle`() {
        val missing =
            browserGapMessage(BrowserGap.CHROME, chromeInstalled = false, usbDebugging = true)
        val idle = browserGapMessage(BrowserGap.CHROME, chromeInstalled = true, usbDebugging = true)
        assertTrue(missing.contains("not installed"))
        assertTrue(idle.contains("Chrome is running"))
        assertTrue(idle.contains("USB debugging"))
    }
}
