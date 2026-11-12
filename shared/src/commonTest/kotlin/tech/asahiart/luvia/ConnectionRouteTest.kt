package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionRouteTest {
    @Test
    fun cgnatEdgesAndOtherTailnetForms() {
        assertEquals(ConnectionRoute.Lan, route("100.63.255.255"))
        assertEquals(ConnectionRoute.Tailnet, route("100.64.0.0"))
        assertEquals(ConnectionRoute.Tailnet, route("100.127.255.255"))
        assertEquals(ConnectionRoute.Lan, route("100.128.0.0"))
        assertEquals(ConnectionRoute.Tailnet, route("fd7a:115c:a1e0::"))
        assertEquals(ConnectionRoute.Tailnet, route("FD7A:115C:A1E0::1"))
        assertEquals(ConnectionRoute.Tailnet, route("yui.java-tiyanki.ts.net"))
        assertEquals(ConnectionRoute.Tailnet, route("studio.example", "studio.example"))
        assertEquals(ConnectionRoute.Tailnet, route("Studio.Example.", "studio.example"))
        assertEquals(ConnectionRoute.Lan, route("192.168.1.83"))
        assertEquals(ConnectionRoute.Lan, route("169.254.12.7"))
        assertEquals(ConnectionRoute.Lan, route("fe80::1"))
        assertEquals(ConnectionRoute.Lan, route("Yui.local", "yui.ts.net"))
    }

    @Test
    fun chipOnlyWhenOnline() {
        val fqdn = "yui.java-tiyanki.ts.net"
        assertNull(connectionRouteOf(HostLink.Idle, fqdn))
        assertNull(connectionRouteOf(HostLink.Connecting, fqdn))
        assertNull(connectionRouteOf(HostLink.Failed(Failure.Transport("timeout")), fqdn))
        assertNull(connectionRouteOf(HostLink.Failed(Failure.ProveRejected("unknown_device", "gone")), fqdn))
        assertNull(connectionRouteOf(HostLink.Failed(Failure.AuthRejected("publickey")), fqdn))
        assertEquals(
            ConnectionRoute.Tailnet,
            connectionRouteOf(HostLink.Online("100.64.0.8", "default"), fqdn),
        )
        assertEquals(
            ConnectionRoute.Lan,
            connectionRouteOf(HostLink.Online("192.168.1.83", "default"), fqdn),
        )
    }

    @Test
    fun runtimeExposesOnlineRouteOnly() {
        val offline = runtime(HostLink.Idle)
        assertNull(offline.connectionRoute)
        val racing = runtime(HostLink.Connecting)
        assertNull(racing.connectionRoute)
        val revoked = runtime(HostLink.Failed(Failure.ProveRejected("unknown_device", "gone")))
        assertNull(revoked.connectionRoute)
        val lan = runtime(HostLink.Online("192.168.1.83", "luvia"))
        assertEquals(ConnectionRoute.Lan, lan.connectionRoute)
        val named = runtime(HostLink.Online("yui.java-tiyanki.ts.net", "luvia"))
        assertEquals(ConnectionRoute.Tailnet, named.connectionRoute)
    }

    private fun route(address: String, fqdn: String? = null): ConnectionRoute? =
        connectionRouteOf(HostLink.Online(address, "default"), fqdn)

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
            freshness = ConnectionFreshness.Offline,
        )
}
