package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.ByteString.Companion.encodeUtf8

class GoldenPairingCodeTest {
    @Test
    fun rejectsTheLiveLuvia1PairingCode() {
        assertFalse('=' in LEGACY)
        val err = assertIs<Outcome.Err<PairingCode>>(PairingCodes.decode(LEGACY))
        val failure = assertIs<Failure.ProtocolError>(err.failure)
        assertEquals("luvia1: pairing codes are not accepted; use luvia2:", failure.reason)
        assertTrue(failure.reason.contains("luvia1:"))
    }

    @Test
    fun luvia2RoundTripKeepsTailnetNameAndHostId() {
        val json =
            """{"name":"Yui","fqdn":"yui.java-tiyanki.ts.net","user":"misaka","port":22,"bridge":"/usr/local/bin/luvia-host","hk":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"],"hints":["192.168.1.16","100.101.102.103"],"host_id":"${"cd".repeat(16)}","device_id":"${"ab".repeat(16)}","device_key":"SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU","role":"controller"}"""
        val code = "luvia2:" + json.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        assertFalse('=' in code)
        val decoded = requireOk(PairingCodes.decode(code))
        assertEquals("Yui", decoded.name)
        assertEquals("yui.java-tiyanki.ts.net", decoded.tailnetFqdn)
        assertEquals("misaka", decoded.user)
        assertEquals(22, decoded.port)
        assertEquals("/usr/local/bin/luvia-host", decoded.bridgePath)
        assertEquals(listOf("192.168.1.16", "100.101.102.103"), decoded.hints)
        assertEquals("cd".repeat(16), decoded.hostId)
        assertEquals("ab".repeat(16), decoded.deviceId)
        assertEquals(HostRole.Controller, decoded.role)
        val upper = code.replace("luvia2:", "LUVIA2:")
        assertEquals(decoded, requireOk(PairingCodes.decode(upper)))
    }

    private fun requireOk(outcome: Outcome<PairingCode>): PairingCode {
        val ok = assertIs<Outcome.Ok<PairingCode>>(outcome)
        return ok.value
    }

    private companion object {
        const val LEGACY: String =
            "luvia1:eyJ2IjoxLCJpZCI6IjA0ZWQxZTczNTk1NjBkNTA1ZmViODI5Zjk5NzFjNGE5IiwiZGsiOiJTSEEyNTY6S3VOWEllUXREYSt5WmxmOTd1QUhWV01QUXNwdzNHVmhlMnAzYzdOVXYzcyIsIm5hbWUiOiJZdWkubG9jYWwiLCJ1c2VyIjoibWlzYWthIiwicG9ydCI6MjIsImFkZHJzIjpbIjE5Mi4xNjguMS4xNiIsIjE3Mi4xOC4wLjEiLCJZdWkubG9jYWwiXSwiaGsiOlsiU0hBMjU2OjQ2bk5sakpMRmhzRmlvMW5sMUJUUWo5b2dSdnJFY2IzSkZIYWxwMVhqRFkiLCJTSEEyNTY6NjJFK2FEM1p2am9CV1ZwQjh2aE9aRzgzM3NaTGNxNHNSRmZIT011eHJ3QSIsIlNIQTI1NjpqVFBxMUwvQnB4R0JGRitmWjM0dU8rSE1vOVdNZTdmV3JlSDBYclIyT3NzIl0sInJvbGUiOiJjb250cm9sbGVyIn0"
    }
}
