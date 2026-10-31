package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingConnectTest {
    @Test
    fun unreachableCopyNamesAddressPortAndHint() {
        assertEquals(
            "连不上 192.168.1.70:22。试试同一 Wi-Fi 或 Tailscale。",
            pairingUnreachableCopy(listOf("192.168.1.70"), 22),
        )
    }

    @Test
    fun unreachableCopyListsEveryAttemptedEndpoint() {
        assertEquals(
            "连不上 192.168.1.70:2222、100.101.1.2:2222。试试同一 Wi-Fi 或 Tailscale。",
            pairingUnreachableCopy(listOf("192.168.1.70", "100.101.1.2"), 2222),
        )
        assertEquals("[fe80::1]:22", formatConnectEndpoint("fe80::1", 22))
    }

    @Test
    fun timeoutBecomesAddressMessageAndAuthDoesNot() {
        val timeout = Failure.Transport("ssh transport failure: connect timeout")
        assertTrue(timeout.isUnreachableTransport())
        assertEquals(
            "连不上 192.168.1.12:2222。试试同一 Wi-Fi 或 Tailscale。",
            pairingConnectMessage(timeout, listOf("192.168.1.12"), 2222),
        )
        val mismatch = Failure.Transport("host key mismatch: expected SHA256:a, actual SHA256:b")
        assertFalse(mismatch.isUnreachableTransport())
        assertEquals("Host key changed. Re-pair this Host.", pairingConnectMessage(mismatch, listOf("10.0.0.1"), 22))
        assertFalse(Failure.Transport("public-key authentication failed").isUnreachableTransport())
    }
}
