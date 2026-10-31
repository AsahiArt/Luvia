package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostStartupTest {
    @Test
    fun coldStartPrefersLastSuccessfulHostOverStaleDuplicate() {
        val stale =
            profile(
                id = "yui-old",
                alias = "Yui",
                status = HostStatus.Stale,
                updated = 9_000,
                lastConnected = "yui.local",
            )
        val lastGood =
            profile(
                id = "yui-local",
                alias = "Yui.local",
                status = HostStatus.Reachable,
                updated = 1_000,
                lastConnected = "Yui.local",
            )
        assertEquals("yui-local", preferredStartupHost(listOf(stale, lastGood))?.id)
        assertEquals("yui-local", preferredStartupHost(listOf(lastGood, stale))?.id)
    }

    @Test
    fun coldStartPrefersNewerReachableHostWhenNamesMatch() {
        val older =
            profile(
                id = "yui-old",
                alias = "Yui",
                status = HostStatus.Reachable,
                updated = 100,
                lastConnected = "yui.local",
            )
        val newer =
            profile(
                id = "yui-local",
                alias = "Yui.local",
                status = HostStatus.Reachable,
                updated = 500,
                lastConnected = "192.168.1.70",
            )
        assertEquals("yui-local", preferredStartupHost(listOf(older, newer))?.id)
    }

    @Test
    fun savedSelectionOfADifferentComputerIsKept() {
        val yui = profile(id = "yui-local", alias = "Yui.local", status = HostStatus.Reachable, updated = 500)
        val nas = profile(id = "nas", alias = "nas", status = HostStatus.Stale, updated = 100, lastConnected = "nas.local")
        assertEquals("nas", resolveStartupHost(listOf(yui, nas), "nas")?.id)
    }

    @Test
    fun savedSelectionOfASimilarNameYieldsLastGoodHost() {
        val stale = profile(id = "yui-old", alias = "Yui", status = HostStatus.Stale, updated = 9_000, lastConnected = "yui.local")
        val lastGood = profile(id = "yui-local", alias = "Yui.local", status = HostStatus.Reachable, updated = 1_000)
        assertEquals("yui-local", resolveStartupHost(listOf(stale, lastGood), "yui-old")?.id)
        assertEquals(lastGood.id, resolveStartupHost(listOf(stale, lastGood), null)?.id)
    }

    @Test
    fun emptyCatalogHasNoStartupHost() {
        assertNull(preferredStartupHost(emptyList()))
        assertNull(resolveStartupHost(emptyList(), "yui-old"))
    }

    @Test
    fun disconnectedHostIsNotPresentedAsLive() {
        val idleCached = HostLink.Idle
        assertEquals(HostChrome.Offline, hostChrome(idleCached, ConnectionFreshness.Stale))
        assertTrue(sessionsAreCached(idleCached))
        assertEquals(HostChrome.Reconnecting, hostChrome(HostLink.Connecting, ConnectionFreshness.Stale))
        assertTrue(sessionsAreCached(HostLink.Connecting))
        val online = HostLink.Online("Yui.local", "default")
        assertEquals(HostChrome.Live, hostChrome(online, ConnectionFreshness.Live))
        assertFalse(sessionsAreCached(online))
        assertEquals(HostChrome.Offline, hostChrome(HostLink.Failed(Failure.Transport("connect timeout")), ConnectionFreshness.Offline))
    }
}

private fun profile(
    id: String,
    alias: String,
    status: HostStatus,
    updated: Long,
    lastConnected: String? = "Yui.local",
): HostProfile =
    HostProfile(
        id = id,
        alias = alias,
        addresses = listOf("192.168.1.70", "Yui.local"),
        sshPort = 22,
        username = "misaka",
        hostKeyFingerprints = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        role = HostRole.Controller,
        lastStatus = status,
        lastUpdatedEpochMs = updated,
        lastConnectedAddress = lastConnected,
        topology = null,
    )
