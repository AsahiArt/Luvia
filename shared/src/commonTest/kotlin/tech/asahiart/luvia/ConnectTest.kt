package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
    fun magicDnsNamesGetNoLocalAlias() {
        assertEquals(
            listOf("studio.tail1234.ts.net"),
            expandUnqualifiedHostnames(listOf("studio.tail1234.ts.net")),
        )
    }

    @Test
    fun orderedAddressesPrefersLiteralsThenHostnames() {
        val profile =
            sample(
                addresses = listOf("Yui.local", "192.168.1.16", "172.18.0.1", "192.168.1.16"),
                lastConnected = "Yui.local",
            )
        assertEquals(
            listOf("192.168.1.16", "172.18.0.1", "Yui.local"),
            orderedAddresses(profile),
        )
    }

    @Test
    fun orderedAddressesPutsLastConnectedLiteralFirst() {
        val profile =
            sample(
                addresses = listOf("192.168.1.16", "192.168.1.33", "Yui.local"),
                lastConnected = "192.168.1.33",
            )
        assertEquals(
            listOf("192.168.1.33", "192.168.1.16", "Yui.local"),
            orderedAddresses(profile),
        )
    }

    @Test
    fun orderedAddressesAppendsLocalForBareHostname() {
        val profile = sample(addresses = listOf("Yui", "192.168.1.16"))
        assertEquals(listOf("192.168.1.16", "Yui", "Yui.local"), orderedAddresses(profile))
    }

    @Test
    fun orderedAddressesDoesNotDuplicateExistingLocal() {
        val profile = sample(addresses = listOf("Yui", "Yui.local"))
        assertEquals(listOf("Yui", "Yui.local"), orderedAddresses(profile))
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

    @Test
    fun skipsNxdomainAndDoesNotMarkHostnameDead() = runTest {
        val attempts = ArrayList<String>()
        val result =
            connectToProfile(
                sample(
                    addresses = listOf("Yui.local", "192.168.1.33"),
                    lastConnected = "Yui.local",
                ),
                DeviceCredential.SoftwareKey("KEY"),
            ) { endpoint, _ ->
                attempts += endpoint.address
                fail(Failure.Transport("ssh transport failure: dns failed"))
            }
        assertEquals(listOf("192.168.1.33", "Yui.local"), attempts)
        assertTrue(result.deadLiteralAddresses.isEmpty())
        val err = result.outcome as Outcome.Err
        assertIs<Failure.Transport>(err.failure)
    }

    @Test
    fun timeoutOnLiteralIsDeadAndContinues() = runTest {
        val attempts = ArrayList<String>()
        val result =
            connectToProfile(
                sample(addresses = listOf("192.168.1.16", "Yui.local", "192.168.1.33")),
                DeviceCredential.SoftwareKey("KEY"),
            ) { endpoint, _ ->
                attempts += endpoint.address
                when (endpoint.address) {
                    "192.168.1.16" -> fail(Failure.Transport("ssh transport failure: connect timeout"))
                    "192.168.1.33" -> fail(Failure.Transport("ssh transport failure: no route"))
                    else -> fail(Failure.Transport("ssh transport failure: dns failed"))
                }
            }
        assertEquals(listOf("192.168.1.16", "192.168.1.33", "Yui.local"), attempts)
        assertEquals(listOf("192.168.1.16", "192.168.1.33"), result.deadLiteralAddresses)
        assertIs<Outcome.Err<*>>(result.outcome)
    }

    @Test
    fun hostKeyMismatchStopsWithoutRetry() = runTest {
        val attempts = ArrayList<String>()
        val result =
            connectToProfile(
                sample(addresses = listOf("10.0.0.1", "10.0.0.2")),
                DeviceCredential.SoftwareKey("KEY"),
            ) { endpoint, _ ->
                attempts += endpoint.address
                fail(Failure.Transport("host key mismatch: expected SHA256:a, actual SHA256:b"))
            }
        assertEquals(listOf("10.0.0.1"), attempts)
        assertTrue(result.deadLiteralAddresses.isEmpty())
        val err = result.outcome as Outcome.Err
        assertTrue((err.failure as Failure.Transport).reason.contains("host key mismatch"))
    }
}

private fun sample(
    addresses: List<String>,
    lastConnected: String? = null,
): HostProfile =
    HostProfile(
        id = "host-1",
        alias = "Yui.local",
        addresses = addresses,
        sshPort = 22,
        username = "misaka",
        hostKeyFingerprints = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 1,
        lastConnectedAddress = lastConnected,
        topology = null,
    )
