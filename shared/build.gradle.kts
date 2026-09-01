import gobley.gradle.GobleyHost
import gobley.gradle.cargo.dsl.android
import gobley.gradle.cargo.dsl.appleMobile
import gobley.gradle.cargo.dsl.jvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.gobleyCargo)
    alias(libs.plugins.gobleyUniffi)
    alias(libs.plugins.kotlinAtomicfu)
    alias(libs.plugins.skie)
}

cargo {
    packageDirectory = rootProject.layout.projectDirectory.dir("transport")
    // Gobley plans a Cargo build per Kotlin target; these blocks only configure them.
    // UniFFI bindgen still uses the host library (`uniffi.generateFromLibrary.build`).
    builds.jvm {
        embedRustLibrary = (rustTarget == GobleyHost.current.rustTarget)
    }
    builds.android {
        embedRustLibrary = true
    }
    builds.appleMobile {
        variants {
            buildTaskProvider.configure {
                additionalEnvironment.put("IPHONEOS_DEPLOYMENT_TARGET", "17.0")
            }
        }
    }
}

uniffi {
    generateFromLibrary {
        namespace = "luvia_transport"
        packageName = "tech.asahiart.luvia.transport"
        build = GobleyHost.current.rustTarget
    }
}

kotlin {
    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "LuviaShared"
            binaryOption("bundleId", "tech.asahiart.luvia.shared")
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.datastore.core)
            implementation(libs.androidx.datastore.core.okio)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "tech.asahiart.luvia.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
