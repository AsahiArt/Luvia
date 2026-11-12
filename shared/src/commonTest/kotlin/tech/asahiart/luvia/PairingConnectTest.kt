package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tech.asahiart.luvia.transport.TransportException

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

    @Test
    fun luvia1CodeIsTheOldCodeMessageNotTheGenericRejection() {
        val old =
            Failure.ProtocolError("luvia1: pairing codes are not accepted; use luvia2:").pairingMessage()
        assertEquals(
            "This is an old pairing code and is no longer supported. Update luvia-host on the computer, run pair again, and scan the new code. The draft is still valid.",
            old,
        )
        assertFalse(old.contains("not a luvia2"))
        val both = Failure.ProtocolError("luvia1: must start with luvia2").pairingMessage()
        assertEquals(old, both)
        assertFalse(both.contains("not a luvia2"))
        val generic = Failure.ProtocolError("pairing code must start with luvia2:").pairingMessage()
        assertTrue(generic.contains("not a luvia2"))
        assertFalse(generic.contains("old pairing code"))
    }

    @Test
    fun dnsTimeoutNamesTheComputerAndCandidateTimeoutStays() {
        assertEquals(
            "Couldn't find this computer (name lookup timed out). Check network or Tailscale.",
            Failure.Transport("dns timeout").connectMessage(),
        )
        assertEquals(
            "No address reachable. Check network or Tailscale.",
            Failure.Transport("candidate timeout").connectMessage(),
        )
        assertEquals(
            "The host refused the connection.",
            Failure.ClosedBeforeBanner().connectMessage(),
        )
        assertEquals(HANDSHAKE_FAILED_EN, Failure.HandshakeFailed("key exchange failed").connectMessage())
        assertEquals(HANDSHAKE_FAILED_ZH, "与主机握手失败")
        val rejected = Failure.ProveRejected("unknown_device", "no pairing grant for host-1")
        assertEquals(PROVE_REJECTED_EN, rejected.connectMessage())
        assertEquals(PROVE_REJECTED_EN, rejected.userMessage())
        assertEquals(PROVE_REJECTED_ZH, "这台设备的配对已失效。在电脑上重新运行 pair，再扫新码。")
        assertFalse(rejected.connectMessage().contains("no pairing grant"))
        assertFalse(rejected.userMessage().contains("unknown_device"))
        assertFalse(rejected.userMessage().contains("host-1"))
    }

    @Test
    fun authRejectedMapsToThePairingInvalidState() {
        val mapped = mapTransport(TransportException.AuthRejected("ssh publickey authentication rejected"))
        val rejected = assertIs<Failure.AuthRejected>(mapped)
        assertEquals("ssh publickey authentication rejected", rejected.reason)
        assertTrue(rejected.isPairingRevoked())
        assertFalse(Failure.Transport("candidate timeout").isPairingRevoked())
        assertEquals(PROVE_REJECTED_EN, rejected.connectMessage())
        assertEquals(PROVE_REJECTED_EN, rejected.userMessage())
        assertEquals(PROVE_REJECTED_ZH, rejected.refusedOutcomeZh())
        val prove = Failure.ProveRejected("unknown_device", "no pairing grant for host-1")
        assertEquals(prove.connectMessage(), rejected.connectMessage())
        assertEquals(prove.userMessage(), rejected.userMessage())
        assertTrue(rejected.raceReason().startsWith("AuthRejected"))
        assertTrue(rejected.raceReason() != prove.raceReason())
        assertFalse(rejected.connectMessage().contains("timeout"))
        assertFalse(rejected.connectMessage().contains("publickey"))
        assertFalse(rejected.userMessage().contains(rejected.reason))
    }

    @Test
    fun setupFailuresStayOnThePairingCopy() {
        assertTrue(Failure.ProtocolError("luvia1: pairing codes are not accepted; use luvia2:").isPairingSetupFailure())
        assertTrue(Failure.ProtocolError("pairing code is for a different device key").isPairingSetupFailure())
        assertTrue(Failure.ProtocolError("Could not store the device key on this device.").isPairingSetupFailure())
        assertTrue(Failure.ProtocolError("short code expired").isPairingSetupFailure())
        assertTrue(Failure.Transport("short code redeem failed").isPairingSetupFailure())
        assertFalse(Failure.Transport("connect timeout").isPairingSetupFailure())
        assertFalse(Failure.ProtocolError("Device key for this host is gone. Re-pair the host to restore access.").isPairingSetupFailure())
    }
}
