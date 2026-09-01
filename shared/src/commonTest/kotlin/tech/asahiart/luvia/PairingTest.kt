package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.ByteString.Companion.encodeUtf8

class PairingTest {
    private val deviceFp = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
    private val hostFp = "SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"

    @Test
    fun decodesHappyPathAndToleratesWhitespaceAndCase() {
        val json = compactJson()
        val token = encode(json)
        val decoded = requireOk(PairingCodes.decode("  $token  "))
        assertEquals(1, decoded.version)
        assertEquals("grant-1", decoded.deviceId)
        assertEquals(deviceFp, decoded.deviceKeyFingerprint)
        assertEquals("studio", decoded.hostLabel)
        assertEquals("misaka", decoded.username)
        assertEquals(22, decoded.sshPort)
        assertEquals(listOf("studio.tailnet", "10.0.0.2"), decoded.addresses)
        assertEquals(listOf(hostFp), decoded.hostKeyFingerprints)
        assertEquals(HostRole.Controller, decoded.role)

        val upper = token.replace("luvia1:", "LUVIA1:")
        assertEquals(decoded, requireOk(PairingCodes.decode(upper)))
    }

    @Test
    fun pairCommandQuotesLabelAndKey() {
        val command = pairCommandFor("misaka's studio", HostRole.Observer, "ssh-ed25519 AAAA key")
        assertEquals(
            "luvia-host pair --name 'misaka'\\''s studio' --role observer --key 'ssh-ed25519 AAAA key'",
            command,
        )
    }

    @Test
    fun rejectsWrongPrefix() {
        assertReason(PairingCodes.decode("https://example"), "pairing code must start with luvia1:")
        assertReason(PairingCodes.decode("luvia2:${encode(compactJson()).substringAfter(':')}"), "pairing code must start with luvia1:")
    }

    @Test
    fun rejectsBadBase64UrlBody() {
        assertReason(PairingCodes.decode("luvia1:!!!!"), "pairing code body is not valid base64url")
        assertReason(PairingCodes.decode("luvia1:abc="), "pairing code body is not valid base64url")
    }

    @Test
    fun rejectsVersionOtherThanOne() {
        assertReason(PairingCodes.decode(encode(compactJson(version = 2))), "pairing code version must be 1")
    }

    @Test
    fun rejectsMalformedJson() {
        assertReason(PairingCodes.decode(encode("{")), "pairing code is not valid JSON")
        assertReason(PairingCodes.decode(encode("[]")), "pairing code is not valid JSON")
    }

    @Test
    fun rejectsEmptyAddresses() {
        assertReason(PairingCodes.decode(encode(compactJson(addrs = "[]"))), "pairing code has no addresses")
    }

    @Test
    fun rejectsEmptyHostKeys() {
        assertReason(PairingCodes.decode(encode(compactJson(hk = "[]"))), "pairing code has no host key fingerprints")
    }

    @Test
    fun rejectsPortOutOfRange() {
        assertReason(PairingCodes.decode(encode(compactJson(port = 0))), "pairing code port is out of range")
        assertReason(PairingCodes.decode(encode(compactJson(port = 65536))), "pairing code port is out of range")
    }

    @Test
    fun rejectsBlankUsername() {
        assertReason(PairingCodes.decode(encode(compactJson(user = ""))), "pairing code username is blank")
    }

    @Test
    fun rejectsInvalidFingerprintShape() {
        assertReason(
            PairingCodes.decode(encode(compactJson(dk = "SHA256:abc"))),
            "pairing code has an invalid host key fingerprint",
        )
        assertReason(
            PairingCodes.decode(encode(compactJson(hk = "[\"md5:aa:bb\"]"))),
            "pairing code has an invalid host key fingerprint",
        )
        assertReason(
            PairingCodes.decode(
                encode(compactJson(hk = "[\"SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\"]")),
            ),
            "pairing code has an invalid host key fingerprint",
        )
    }

    private fun compactJson(
        version: Int = 1,
        user: String = "misaka",
        port: Int = 22,
        addrs: String = "[\"studio.tailnet\",\"10.0.0.2\"]",
        hk: String = "[\"$hostFp\"]",
        dk: String = deviceFp,
    ): String =
        """{"v":$version,"id":"grant-1","dk":"$dk","name":"studio","user":"$user","port":$port,"addrs":$addrs,"hk":$hk,"role":"controller"}"""

    private fun encode(json: String): String {
        val body = json.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        return "luvia1:$body"
    }

    private fun requireOk(outcome: Outcome<PairingCode>): PairingCode {
        val ok = assertIs<Outcome.Ok<PairingCode>>(outcome)
        return ok.value
    }

    private fun assertReason(outcome: Outcome<PairingCode>, reason: String) {
        val err = assertIs<Outcome.Err<PairingCode>>(outcome)
        val failure = assertIs<Failure.ProtocolError>(err.failure)
        assertEquals(reason, failure.reason)
        assertTrue(failure.reason.isNotBlank())
    }
}
