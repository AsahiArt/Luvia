package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.asahiart.luvia.ui.ConnectionBadge

class HostUiModelMappingTest {
    @Test
    fun onlineRouteIsExposedAndHiddenUnlessOnline() {
        val lan = runtime(HostLink.Online("192.168.1.83", "luvia")).toUi()
        assertEquals(ConnectionBadge.Live, lan.connection)
        assertEquals("192.168.1.83", lan.address)
        assertEquals(ConnectionRoute.Lan, lan.route)

        val namedOnly = runtime(HostLink.Online("yui.java-tiyanki.ts.net", "luvia")).toUi()
        assertEquals(ConnectionRoute.Unknown, namedOnly.route)
        assertEquals("yui.java-tiyanki.ts.net", namedOnly.address)

        val tailnet = runtime(HostLink.Online("studio.example", "luvia", "100.64.0.8")).toUi()
        assertEquals(ConnectionRoute.Tailnet, tailnet.route)
        assertEquals("studio.example", tailnet.address)

        val namedLan = runtime(HostLink.Online("yui.java-tiyanki.ts.net", "luvia", "192.168.1.83")).toUi()
        assertEquals(ConnectionRoute.Lan, namedLan.route)
        assertEquals("yui.java-tiyanki.ts.net", namedLan.address)

        val connecting = runtime(HostLink.Connecting).toUi()
        assertEquals(ConnectionBadge.Connecting, connecting.connection)
        assertNull(connecting.route)

        val offline = runtime(HostLink.Failed(Failure.Transport("timeout"))).toUi()
        assertEquals(ConnectionBadge.Offline, offline.connection)
        assertNull(offline.route)
        assertEquals("100.64.0.8", offline.address)

        val revoked = runtime(HostLink.Failed(Failure.AuthRejected("publickey"))).toUi()
        assertTrue(revoked.pairingRevoked)
        assertNull(revoked.route)
    }

    private fun runtime(link: HostLink): HostRuntime =
        HostRuntime(
            profile = HostProfile(
                id = "h",
                hostId = "ab".repeat(16),
                name = "Yui.local",
                tailnetFqdn = "yui.java-tiyanki.ts.net",
                user = "misaka",
                hostKeys = emptySet(),
                bridgePath = "/usr/local/bin/luvia-host",
                role = HostRole.Controller,
                lastStatus = HostStatus.Reachable,
                lastUpdatedEpochMs = 0,
                topology = null,
                hints = listOf("192.168.1.83", "100.64.0.8"),
                port = 22,
                lastGood = "100.64.0.8",
            ),
            link = link,
            snapshot = null,
            tasks = emptyList(),
            freshness = if (link is HostLink.Online) ConnectionFreshness.Live else ConnectionFreshness.Offline,
        )
}
