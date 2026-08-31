import Foundation
import Security

enum DeviceKeyStoreError: Error {
    case unexpectedStatus(OSStatus)
    case invalidData
}

struct DeviceKeyStore {
    private let service = "tech.asahiart.luvia.device-keys"

    func save(_ privateKey: Data, deviceID: String) throws {
        let query = baseQuery(deviceID: deviceID)
        SecItemDelete(query as CFDictionary)

        var insert = query
        insert[kSecValueData as String] = privateKey
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw DeviceKeyStoreError.unexpectedStatus(status)
        }
    }

    func load(deviceID: String) throws -> Data? {
        var query = baseQuery(deviceID: deviceID)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var value: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw DeviceKeyStoreError.unexpectedStatus(status)
        }
        guard let data = value as? Data else {
            throw DeviceKeyStoreError.invalidData
        }
        return data
    }

    func delete(deviceID: String) throws {
        let status = SecItemDelete(baseQuery(deviceID: deviceID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceKeyStoreError.unexpectedStatus(status)
        }
    }

    private func baseQuery(deviceID: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceID,
            kSecAttrSynchronizable as String: false,
        ]
    }
}
