package tech.asahiart.luvia

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

public fun DeviceKeyVault(directory: String): DeviceKeyVault = JvmDeviceKeyVault(directory)

private class JvmDeviceKeyVault(directory: String) : DeviceKeyVault {
    private val root = File(directory)

    override fun save(deviceId: String, privateKeyOpenssh: String) {
        root.mkdirs()
        val file = fileFor(deviceId)
        val bytes = privateKeyOpenssh.encodeToByteArray()
        try {
            val tmp = File(root, ".${file.name}.tmp")
            tmp.writeBytes(bytes)
            setOwnerReadWrite(tmp)
            if (file.exists()) {
                file.delete()
            }
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
            setOwnerReadWrite(file)
        } finally {
            bytes.fill(0)
        }
    }

    override fun credential(deviceId: String): DeviceCredential? {
        val file = fileFor(deviceId)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val pem =
            try {
                bytes.decodeToString()
            } finally {
                bytes.fill(0)
            }
        return DeviceCredential.SoftwareKey(pem)
    }

    override fun delete(deviceId: String) {
        val file = fileFor(deviceId)
        if (file.exists()) {
            file.delete()
        }
    }

    override fun deviceIds(): List<String> {
        val files = root.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile || file.name.startsWith(".")) return@mapNotNull null
            decodeDeviceId(file.name)
        }
    }

    private fun fileFor(deviceId: String): File = File(root, encodeDeviceId(deviceId))
}

private fun encodeDeviceId(deviceId: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(deviceId.encodeToByteArray())

private fun decodeDeviceId(filename: String): String? =
    try {
        Base64.getUrlDecoder().decode(filename).decodeToString()
    } catch (_: Exception) {
        null
    }

private fun setOwnerReadWrite(file: File) {
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
