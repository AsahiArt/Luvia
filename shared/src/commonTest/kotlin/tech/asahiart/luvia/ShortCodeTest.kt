package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random

class ShortCodeTest {
    private val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"

    @Test
    fun looksLikeShortCodeIsSixDigitsOnly() {
        assertTrue(looksLikeShortCode("482197"))
        assertTrue(looksLikeShortCode("  000001 "))
        assertFalse(looksLikeShortCode("48219"))
        assertFalse(looksLikeShortCode("4821971"))
        assertFalse(looksLikeShortCode("48 2197"))
        assertFalse(looksLikeShortCode("luvia1:abc"))
        assertFalse(looksLikeShortCode("abcdef"))
        assertFalse(PairingCodes.looksLikeCode("482197"))
    }

    @Test
    fun parseShortCodeHostAcceptsV4V6AndPort() {
        assertEquals(ShortCodeEndpoint("192.168.1.4", 8741), parseShortCodeHost("192.168.1.4"))
        assertEquals(ShortCodeEndpoint("192.168.1.4", 9000), parseShortCodeHost("192.168.1.4:9000"))
        assertEquals(ShortCodeEndpoint("studio.local", 8741), parseShortCodeHost("  studio.local  "))
        assertEquals(ShortCodeEndpoint("fe80::1", 8741), parseShortCodeHost("[fe80::1]"))
        assertEquals(ShortCodeEndpoint("fe80::1", 8741), parseShortCodeHost("[fe80::1]:8741"))
        assertEquals(ShortCodeEndpoint("fe80::1", 8741), parseShortCodeHost("fe80::1"))
        assertNull(parseShortCodeHost(""))
        assertNull(parseShortCodeHost("192.168.1.4:0"))
        assertNull(parseShortCodeHost("192.168.1.4:99999"))
        assertEquals(DEFAULT_SHORT_REDEEM_PORT, 8741)
    }

    @Test
    fun redeemShortCodeReturnsLuvia1FromAKnownHost() = runTest {
        val luvia = sampleLuvia1()
        var seenUrl = ""
        var seenBody = ""
        val redeemed =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("192.168.1.4", 8741)),
                ShortCodePoster { url, body ->
                    seenUrl = url
                    seenBody = body
                    ShortCodeHttp(200, """{"luvia1":"$luvia"}""")
                },
            )
        assertEquals("http://192.168.1.4:8741/pair/redeem", seenUrl)
        assertTrue(seenBody.contains("\"code\":\"482197\""))
        assertTrue(seenBody.contains(fingerprint))
        assertEquals(luvia, (redeemed as Outcome.Ok).value)
        assertEquals(fingerprint, (PairingCodes.decode(luvia) as Outcome.Ok).value.deviceKeyFingerprint)
    }

    @Test
    fun redeemMapsHostErrorsAndDoesNotCallTheNetworkForABadCode() = runTest {
        val expired =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("10.0.0.2", 8741)),
                ShortCodePoster { _, _ -> ShortCodeHttp(410, """{"error":"expired"}""") },
            )
        assertEquals("short code expired", protocolReason(expired))
        val exhausted =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("10.0.0.2", 8741)),
                ShortCodePoster { _, _ -> ShortCodeHttp(410, """{"error":"exhausted"}""") },
            )
        assertEquals("short code was invalidated after failed attempts", protocolReason(exhausted))
        val mismatch =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("10.0.0.2", 8741)),
                ShortCodePoster { _, _ -> ShortCodeHttp(403, """{"error":"dk_mismatch"}""") },
            )
        assertEquals("short code device key does not match", protocolReason(mismatch))
        val down =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("10.0.0.2", 8741)),
                ShortCodePoster { _, _ -> ShortCodeHttp(0, "connection refused") },
            )
        val downFailure = (down as Outcome.Err).failure
        assertIs<Failure.Transport>(downFailure)
        assertTrue(downFailure.isPairingSetupFailure())
        var called = false
        val skipped =
            redeemShortCode(
                "12345",
                fingerprint,
                listOf(ShortCodeEndpoint("10.0.0.2", 8741)),
                ShortCodePoster { _, _ ->
                    called = true
                    ShortCodeHttp(200, "{}")
                },
            )
        assertFalse(called)
        assertEquals("short code was rejected", protocolReason(skipped))
        assertEquals(
            "short code needs the computer address",
            protocolReason(redeemShortCode("482197", fingerprint, "  ", 8741)),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun redeemedLuvia1CompletesPairingThroughTheExistingPath() = runTest {
        val luvia = sampleLuvia1()
        val redeemed =
            redeemShortCode(
                "482197",
                fingerprint,
                listOf(ShortCodeEndpoint("192.168.1.70", 8741)),
                ShortCodePoster { _, _ -> ShortCodeHttp(200, """{"luvia1":"$luvia"}""") },
            )
        val code = (redeemed as Outcome.Ok).value
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-short-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val manager = HostManager(store, ShortUnreadableVault(), backgroundScope)
        val draft =
            PairingDraft(
                deviceLabel = "phone",
                role = HostRole.Controller,
                authorizedKeysLine = "ssh-ed25519 AAAA",
                deviceKeyFingerprint = fingerprint,
                command = "luvia-host pair --short",
                privateKeyOpenssh = "not-a-key",
            )
        val result = manager.completePairing(draft, code)
        advanceUntilIdle()
        val err = result as Outcome.Err
        assertFalse(err.failure.isPairingSetupFailure())
        assertTrue((err.failure as Failure.ProtocolError).reason.contains("Re-pair"))
        assertTrue(store.current().hosts.isEmpty())
        manager.close()
    }

    private fun sampleLuvia1(): String {
        val body =
            """{"v":1,"id":"grant-short","dk":"$fingerprint","name":"studio","user":"misaka","port":22,"addrs":["192.168.1.70"],"hk":["$fingerprint"],"role":"controller"}"""
        return "luvia1:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
    }

    private fun protocolReason(outcome: Outcome<String>): String {
        val err = assertIs<Outcome.Err<String>>(outcome)
        return (err.failure as Failure.ProtocolError).reason
    }
}

private class ShortUnreadableVault : DeviceKeyVault {
    override fun save(deviceId: String, privateKeyOpenssh: String) {}

    override fun credential(deviceId: String): DeviceCredential? = null

    override fun delete(deviceId: String) {}

    override fun deviceIds(): List<String> = emptyList()
}
