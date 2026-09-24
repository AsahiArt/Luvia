// Device ABI by default. Emulator x86_64 is opt-in:
//   -Pluvia.androidEmulator=true
//   LUVIA_ANDROID_EMULATOR=1
val raw = (findProperty("luvia.androidEmulator") as String?)
    ?: System.getenv("LUVIA_ANDROID_EMULATOR")
val emulator = raw == "1" || raw.equals("true", ignoreCase = true)
extra["luviaAndroidAbis"] = if (emulator) {
    listOf("arm64-v8a", "x86_64")
} else {
    listOf("arm64-v8a")
}
