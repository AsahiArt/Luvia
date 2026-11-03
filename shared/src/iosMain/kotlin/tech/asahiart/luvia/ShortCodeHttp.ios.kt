package tech.asahiart.luvia

import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.setValue
import platform.posix.memcpy

internal actual fun platformShortCodePoster(): ShortCodePoster =
    ShortCodePoster { url, body -> postShortRedeem(url, body) }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private suspend fun postShortRedeem(url: String, body: String): ShortCodeHttp =
    suspendCancellableCoroutine { continuation ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            continuation.resume(ShortCodeHttp(0, "short code redeem failed"))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest(uRL = nsUrl)
        request.setHTTPMethod("POST")
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        request.setTimeoutInterval(5.0)
        val payload = body.encodeToByteArray()
        request.setHTTPBody(payload.toNSData())
        val task =
            NSURLSession.sharedSession.dataTaskWithRequest(request) { responseData, response, error ->
                if (!continuation.isActive) return@dataTaskWithRequest
                val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                val text = responseData?.toUtf8().orEmpty()
                continuation.resume(
                    if (error != null && status == 0) {
                        ShortCodeHttp(0, error.localizedDescription)
                    } else {
                        ShortCodeHttp(status, text)
                    },
                )
            }
        task.resume()
        continuation.invokeOnCancellation { task.cancel() }
    }

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toUtf8(): String {
    val size = length.toInt()
    if (size == 0 || bytes == null) return ""
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out.decodeToString()
}
