package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectTest {
    @Test
    fun tailnetAddressesAreRecognised() {
        assertTrue(isTailnetAddress("100.101.102.103"))
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertFalse(isTailnetAddress("100.128.0.1"))
        assertFalse(isTailnetAddress("192.168.1.10"))
        assertTrue(isTailnetAddress("fd7a:115c:a1e0::1"))
        assertFalse(isTailnetAddress("fd00::1"))
        assertTrue(isTailnetAddress("studio.tail1234.ts.net"))
        assertFalse(isTailnetAddress("studio.local"))
        assertFalse(isTailnetAddress("studio"))
    }

    @Test
    fun isLiteralIpAcceptsV4AndV6() {
        assertTrue(isLiteralIp("192.168.1.33"))
        assertTrue(isLiteralIp("127.0.0.1"))
        assertTrue(isLiteralIp("::1"))
        assertTrue(isLiteralIp("[fe80::1]"))
        assertFalse(isLiteralIp("Yui.local"))
        assertFalse(isLiteralIp("studio.tailnet"))
        assertFalse(isLiteralIp("256.0.0.1"))
    }
}
