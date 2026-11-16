package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionRouteTest {
    @Test
    fun cgnatEdgesAndOtherTailnetForms() {
        assertEquals(ConnectionRoute.Unknown, route("100.63.255.255"))
        assertEquals(ConnectionRoute.Tailnet, route("100.64.0.0"))
        assertEquals(ConnectionRoute.Tailnet, route("100.127.255.255"))
        assertEquals(ConnectionRoute.Unknown, route("100.128.0.0"))
        assertEquals(ConnectionRoute.Tailnet, route("fd7a:115c:a1e0::"))
        assertEquals(ConnectionRoute.Tailnet, route("FD7A:115C:A1E0::1"))
        assertEquals(ConnectionRoute.Unknown, route("yui.java-tiyanki.ts.net"))
        assertEquals(ConnectionRoute.Unknown, route("studio.example", "studio.example"))
        assertEquals(ConnectionRoute.Unknown, route("Studio.Example.", "studio.example"))
        assertEquals(ConnectionRoute.Lan, route("192.168.1.83"))
        assertEquals(ConnectionRoute.Lan, route("10.1.2.3"))
        assertEquals(ConnectionRoute.Lan, route("172.16.0.1"))
        assertEquals(ConnectionRoute.Unknown, route("172.32.0.1"))
        assertEquals(ConnectionRoute.Lan, route("169.254.12.7"))
        assertEquals(ConnectionRoute.Lan, route("fe80::1"))
        assertEquals(ConnectionRoute.Lan, route("fd00::1"))
        assertEquals(ConnectionRoute.Unknown, route("Yui.local", "yui.ts.net"))
        assertEquals(ConnectionRoute.Unknown, route("8.8.8.8"))
        assertEquals(ConnectionRoute.Unknown, route("2001:db8::1"))
    }

    @Test
    fun ipv4MappedAddressesUseTheEmbeddedDestination() {
        assertEquals(ConnectionRoute.Tailnet, route("::ffff:100.64.0.0"))
        assertEquals(ConnectionRoute.Tailnet, route("::ffff:100.64.0.8"))
        assertEquals(ConnectionRoute.Tailnet, route("::FFFF:100.127.255.255"))
        assertEquals(ConnectionRoute.Tailnet, route("[::ffff:100.64.0.8]"))
        assertEquals(ConnectionRoute.Tailnet, route("::ffff:6440:8"))
        assertEquals(ConnectionRoute.Tailnet, route("0:0:0:0:0:ffff:100.64.0.8"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:100.63.255.255"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:643f:ffff"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:100.128.0.0"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:6480:0"))
        assertEquals(ConnectionRoute.Lan, route("::ffff:192.168.1.83"))
        assertEquals(ConnectionRoute.Lan, route("::ffff:10.0.0.8"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:8.8.8.8"))
        assertEquals(ConnectionRoute.Unknown, route("::ffff:1.2.3.4"))
    }

    @Test
    fun dialedIpClassifiesAndCandidateNameStaysPut() {
        val studio = HostLink.Online("studio.example", "default", "100.64.0.8")
        assertEquals("studio.example", studio.address)
        assertEquals(ConnectionRoute.Tailnet, connectionRouteOf(studio, "yui.java-tiyanki.ts.net"))
        val namedLan = HostLink.Online("yui.java-tiyanki.ts.net", "default", "192.168.1.83")
        assertEquals("yui.java-tiyanki.ts.net", namedLan.address)
        assertEquals(ConnectionRoute.Lan, connectionRouteOf(namedLan, "yui.java-tiyanki.ts.net"))
        val publicDest = HostLink.Online("studio.example", "default", "203.0.113.9")
        assertEquals(ConnectionRoute.Unknown, connectionRouteOf(publicDest, "studio.example"))
        val mapped = HostLink.Online("studio.example", "default", "::ffff:100.64.0.8")
        assertEquals(ConnectionRoute.Tailnet, connectionRouteOf(mapped, null))
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
        assertEquals(ConnectionRoute.Unknown, named.connectionRoute)
        val dialed = runtime(HostLink.Online("yui.java-tiyanki.ts.net", "luvia", "100.64.0.8"))
        assertEquals(ConnectionRoute.Tailnet, dialed.connectionRoute)
        assertEquals("yui.java-tiyanki.ts.net", (dialed.link as HostLink.Online).address)
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
