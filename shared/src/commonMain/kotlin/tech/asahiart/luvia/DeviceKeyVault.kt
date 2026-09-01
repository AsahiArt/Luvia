package tech.asahiart.luvia

// Software keys for Beta. A hardware-backed variant (Secure Enclave / StrongBox)
// is the intended second implementation and must not require caller changes.
public sealed interface DeviceCredential {
    public class SoftwareKey internal constructor(internal val privateKeyOpenssh: String) : DeviceCredential
}

public interface DeviceKeyVault {
    public fun save(deviceId: String, privateKeyOpenssh: String)

    public fun credential(deviceId: String): DeviceCredential?

    public fun delete(deviceId: String)

    public fun deviceIds(): List<String>
}
