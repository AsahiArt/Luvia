package tech.asahiart.luvia

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
public fun DeviceKeyVault(service: String = "tech.asahiart.luvia.device-keys"): DeviceKeyVault =
    IosDeviceKeyVault(service)

@OptIn(ExperimentalForeignApi::class)
private class IosDeviceKeyVault(private val service: String) : DeviceKeyVault {
    override fun save(deviceId: String, privateKeyOpenssh: String) {
        val bytes = privateKeyOpenssh.encodeToByteArray()
        try {
            val data = bytes.toNSData()
            withQuery { query ->
                addBase(query, deviceId)
                SecItemDelete(query)
            }
            withQuery { query ->
                addBase(query, deviceId)
                query.addBridged(kSecValueData, data)
                query.addConst(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                checkStatus(SecItemAdd(query, null), allowNotFound = false)
            }
        } finally {
            bytes.fill(0)
        }
    }

    override fun credential(deviceId: String): DeviceCredential? {
        return withQuery { query ->
            addBase(query, deviceId)
            query.addConst(kSecReturnData, kCFBooleanTrue)
            query.addConst(kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@withQuery null
                checkStatus(status, allowNotFound = false)
                val data = CFBridgingRelease(result.value) as? NSData ?: return@withQuery null
                val plaintext = data.toByteArray()
                val pem =
                    try {
                        plaintext.decodeToString()
                    } finally {
                        plaintext.fill(0)
                    }
                DeviceCredential.SoftwareKey(pem)
            }
        }
    }

    override fun delete(deviceId: String) {
        withQuery { query ->
            addBase(query, deviceId)
            checkStatus(SecItemDelete(query), allowNotFound = true)
        }
    }

    override fun deviceIds(): List<String> {
        return withQuery { query ->
            query.addConst(kSecClass, kSecClassGenericPassword)
            query.addBridged(kSecAttrService, service)
            query.addConst(kSecAttrSynchronizable, kCFBooleanFalse)
            query.addConst(kSecReturnAttributes, kCFBooleanTrue)
            query.addConst(kSecMatchLimit, kSecMatchLimitAll)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@withQuery emptyList()
                checkStatus(status, allowNotFound = false)
                val array = CFBridgingRelease(result.value) as? NSArray ?: return@withQuery emptyList()
                val accountKey = kSecAttrAccount!!.toKotlinString()
                (0 until array.count.toInt()).mapNotNull { index ->
                    val attrs = array.objectAtIndex(index.toULong()) as? NSDictionary ?: return@mapNotNull null
                    attrs.objectForKey(accountKey) as? String
                }
            }
        }
    }

    private fun addBase(query: CFMutableDictionaryRef, deviceId: String) {
        query.addConst(kSecClass, kSecClassGenericPassword)
        query.addBridged(kSecAttrService, service)
        query.addBridged(kSecAttrAccount, deviceId)
        query.addConst(kSecAttrSynchronizable, kCFBooleanFalse)
    }

    private fun checkStatus(status: OSStatus, allowNotFound: Boolean) {
        if (status == errSecSuccess) return
        if (allowNotFound && status == errSecItemNotFound) return
        throw IllegalStateException("keychain status $status")
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
    val query = memScoped {
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
    } ?: error("CFDictionaryCreateMutable failed")
    try {
        return block(query)
    } finally {
        CFRelease(query)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFMutableDictionaryRef.addConst(key: COpaquePointer?, value: COpaquePointer?) {
    CFDictionaryAddValue(this, key, value)
}

@OptIn(ExperimentalForeignApi::class)
private fun CFMutableDictionaryRef.addBridged(key: COpaquePointer?, value: Any?) {
    val retained = CFBridgingRetain(value)
    CFDictionaryAddValue(this, key, retained)
    if (retained != null) {
        CFRelease(retained)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFStringRef.toKotlinString(): String = CFBridgingRelease(CFRetain(this)) as String

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    this.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val count = this.length.toInt()
    if (count == 0) return ByteArray(0)
    val out = ByteArray(count)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}
