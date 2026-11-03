package tech.asahiart.luvia

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun platformShortCodePoster(): ShortCodePoster =
    ShortCodePoster { url, body -> postShortRedeem(url, body) }

private suspend fun postShortRedeem(url: String, body: String): ShortCodeHttp =
    withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { stream ->
                stream.write(body.encodeToByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ShortCodeHttp(status, text)
        } catch (error: Exception) {
            ShortCodeHttp(0, error.message ?: "short code redeem failed")
        } finally {
            connection.disconnect()
        }
    }
