package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import okio.ByteString.Companion.encodeUtf8

class PairingTest {
    private val deviceFp = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
    private val hostFp = "SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"

    @Test
    fun decodesHappyPathAndToleratesWhitespaceAndCase() {
        val json = compactJson()
        val token = encode(json)
        val decoded = requireOk(PairingCodes.decode("  $token  "))
        assertEquals("studio", decoded.name)
        assertEquals("studio.tailnet", decoded.tailnetFqdn)
        assertEquals("misaka", decoded.user)
        assertEquals(22, decoded.port)
        assertEquals("/usr/local/bin/luvia-host", decoded.bridgePath)
        assertEquals(listOf("10.0.0.2", "100.64.0.8"), decoded.hints)
        assertEquals(listOf(hostFp), decoded.hostKeys)
        assertEquals("cd".repeat(16), decoded.hostId)
        assertEquals("ab".repeat(16), decoded.deviceId)
        assertEquals(deviceFp, decoded.deviceKey)
        assertEquals(HostRole.Controller, decoded.role)

        val upper = token.replace("luvia2:", "LUVIA2:")
        assertEquals(decoded, requireOk(PairingCodes.decode(upper)))
    }

    @Test
    fun shortFingerprintIsTheFirstSixBytesGrouped() {
        assertEquals("E3B0 C442 98FC", shortFingerprint(deviceFp))
        assertEquals("E3B0 C442 98FC", shortFingerprint("  $deviceFp  "))
    }

    @Test
    fun pairCommandQuotesLabelAndKey() {
        val command = pairCommandFor("misaka's studio", HostRole.Observer, "ssh-ed25519 AAAA key")
        assertEquals(
            "luvia-host pair --name 'misaka'\\''s studio' --role observer --key 'ssh-ed25519 AAAA key' --short",
            command,
        )
    }

    @Test
    fun looksLikeCodeRequiresLuviaPrefix() {
        assertTrue(PairingCodes.looksLikeCode("  luvia2:abc  "))
        assertTrue(PairingCodes.looksLikeCode("LUVIA2:abc"))
        assertFalse(PairingCodes.looksLikeCode(""))
        assertFalse(PairingCodes.looksLikeCode("luvia-host pair --name 'phone' --role controller --key 'ssh-ed25519 AAAA'"))
        assertFalse(PairingCodes.looksLikeCode("https://example"))
        assertFalse(PairingCodes.looksLikeCode("luvia1:abc"))
    }

    @Test
    fun rejectsLuvia1AndOtherPrefixes() {
        assertReason(PairingCodes.decode("https://example"), "pairing code must start with luvia2:")
        assertReason(
            PairingCodes.decode("luvia1:${encode(compactJson()).substringAfter(':')}"),
            "luvia1: pairing codes are not accepted; use luvia2:",
        )
    }

    @Test
    fun rejectsBadBase64UrlBody() {
        assertReason(PairingCodes.decode("luvia2:!!!!"), "pairing code body is not valid base64url")
        assertReason(PairingCodes.decode("luvia2:abc="), "pairing code body is not valid base64url")
    }

    @Test
    fun rejectsUnknownField() {
        val json = compactJson().dropLast(1) + "," + "\"v\":1}"
        assertReason(PairingCodes.decode(encode(json)), "pairing code contains unknown field 'v'")
    }

    @Test
    fun rejectsMalformedJson() {
        assertReason(PairingCodes.decode(encode("{")), "pairing code is not valid JSON")
        assertReason(PairingCodes.decode(encode("[]")), "pairing code is not valid JSON")
    }

    @Test
    fun rejectsEmptyHintsWithoutTailnetName() {
        assertReason(
            PairingCodes.decode(encode(compactJson(hints = "[]", fqdn = "null"))),
            "pairing code has no hints or tailnet name",
        )
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
            PairingCodes.decode(encode(compactJson(deviceKey = "SHA256:abc"))),
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
        user: String = "misaka",
        port: Int = 22,
        hints: String = "[\"10.0.0.2\",\"100.64.0.8\"]",
        hk: String = "[\"$hostFp\"]",
        deviceKey: String = deviceFp,
        fqdn: String = "\"studio.tailnet\"",
    ): String =
        """{"name":"studio","fqdn":$fqdn,"user":"$user","port":$port,"bridge":"/usr/local/bin/luvia-host","hk":$hk,"hints":$hints,"host_id":"${"cd".repeat(16)}","device_id":"${"ab".repeat(16)}","device_key":"$deviceKey","role":"controller"}"""

    private fun encode(json: String): String {
        val body = json.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        return "luvia2:$body"
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
