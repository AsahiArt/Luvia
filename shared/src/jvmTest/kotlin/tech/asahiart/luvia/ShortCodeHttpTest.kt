package tech.asahiart.luvia

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8

class ShortCodeHttpTest {
    @Test
    fun redeemShortCodePostsToAKnownAddressAndReturnsLuvia1() = runTest {
        val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        val hostId = "cd".repeat(16)
        val deviceId = "ab".repeat(16)
        val body =
            """{"name":"studio","user":"misaka","port":22,"bridge":"/usr/local/bin/luvia-host","hk":["$fingerprint"],"hints":["127.0.0.1"],"host_id":"$hostId","device_id":"$deviceId","device_key":"$fingerprint","role":"controller"}"""
        val luvia = "luvia2:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seen = ""
        server.createContext("/pair/redeem") { exchange ->
            seen = exchange.requestBody.readBytes().decodeToString()
            val bytes = """{"luvia2":"$luvia"}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val result = assertIs<Outcome.Ok<String>>(redeemShortCode("482197", fingerprint, "127.0.0.1", port))
            assertEquals(luvia, result.value)
            assertTrue(seen.contains("\"code\":\"482197\""))
            assertTrue(seen.contains(fingerprint))
            val decoded = assertIs<Outcome.Ok<PairingCode>>(PairingCodes.decode(result.value))
            assertEquals(fingerprint, decoded.value.deviceKey)
            assertEquals(listOf("127.0.0.1"), decoded.value.hints)
        } finally {
            server.stop(0)
        }
    }
}
