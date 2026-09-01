package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GoldenPairingCodeTest {
    @Test
    fun parsesLiveLuviaHostPairingCode() {
        assertFalse('=' in GOLDEN)
        val decoded = requireOk(PairingCodes.decode(GOLDEN))
        assertEquals(1, decoded.version)
        assertEquals("04ed1e7359560d505feb829f9971c4a9", decoded.deviceId)
        assertEquals("SHA256:KuNXIeQtDa+yZlf97uAHVWMPQspw3GVhe2p3c7NUv3s", decoded.deviceKeyFingerprint)
        assertEquals("Yui.local", decoded.hostLabel)
        assertEquals("misaka", decoded.username)
        assertEquals(22, decoded.sshPort)
        assertEquals(listOf("192.168.1.16", "172.18.0.1", "Yui.local"), decoded.addresses)
        assertEquals(
            listOf(
                "SHA256:46nNljJLFhsFio1nl1BTQj9ogRvrEcb3JFHalp1XjDY",
                "SHA256:62E+aD3ZvjoBWVpB8vhOZG833sZLcq4sRFfHOMuxrwA",
                "SHA256:jTPq1L/BpxGBFF+fZ34uO+HMo9WMe7fWreH0XrR2Oss",
            ),
            decoded.hostKeyFingerprints,
        )
        assertEquals(HostRole.Controller, decoded.role)
    }

    @Test
    fun prefixMatchIsCaseInsensitiveAndBodyIsUnpadded() {
        assertFalse(GOLDEN.contains("="))
        val upper = GOLDEN.replace("luvia1:", "LUVIA1:")
        val mixed = GOLDEN.replace("luvia1:", "LuViA1:")
        assertEquals(requireOk(PairingCodes.decode(GOLDEN)), requireOk(PairingCodes.decode(upper)))
        assertEquals(requireOk(PairingCodes.decode(GOLDEN)), requireOk(PairingCodes.decode(mixed)))
    }

    private fun requireOk(outcome: Outcome<PairingCode>): PairingCode {
        val ok = assertIs<Outcome.Ok<PairingCode>>(outcome)
        return ok.value
    }

    private companion object {
        const val GOLDEN: String =
            "luvia1:eyJ2IjoxLCJpZCI6IjA0ZWQxZTczNTk1NjBkNTA1ZmViODI5Zjk5NzFjNGE5IiwiZGsiOiJTSEEyNTY6S3VOWEllUXREYSt5WmxmOTd1QUhWV01QUXNwdzNHVmhlMnAzYzdOVXYzcyIsIm5hbWUiOiJZdWkubG9jYWwiLCJ1c2VyIjoibWlzYWthIiwicG9ydCI6MjIsImFkZHJzIjpbIjE5Mi4xNjguMS4xNiIsIjE3Mi4xOC4wLjEiLCJZdWkubG9jYWwiXSwiaGsiOlsiU0hBMjU2OjQ2bk5sakpMRmhzRmlvMW5sMUJUUWo5b2dSdnJFY2IzSkZIYWxwMVhqRFkiLCJTSEEyNTY6NjJFK2FEM1p2am9CV1ZwQjh2aE9aRzgzM3NaTGNxNHNSRmZIT011eHJ3QSIsIlNIQTI1NjpqVFBxMUwvQnB4R0JGRitmWjM0dU8rSE1vOVdNZTdmV3JlSDBYclIyT3NzIl0sInJvbGUiOiJjb250cm9sbGVyIn0"
    }
}
