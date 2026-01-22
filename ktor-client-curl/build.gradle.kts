import com.ensody.nativebuilds.cinterops
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    alias(libs.plugins.nativebuilds)
}

setupBuildLogic {
    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        if (project.isNativeEnabled) {
            sourceSets["desktopMain"].dependencies {
                api(libs.ktor.client.core)
                api(libs.ktor.client.cio)
                api(libs.nativebuilds.curl.libcurl)
                api(libs.nativebuilds.nghttp2.libnghttp2)
                api(libs.nativebuilds.nghttp3.libnghttp3)
                api(libs.nativebuilds.ngtcp2.libngtcp2)
                api(libs.nativebuilds.ngtcp2.libngtcp2CryptoOssl)
                api(libs.nativebuilds.zlib.libz)
                api(libs.nativebuilds.openssl.libcrypto)
                api(libs.nativebuilds.openssl.libssl)
            }
        }

        cinterops(libs.nativebuilds.curl.headers) {
            definitionFile.set(file("src/desktopMain/cinterop/libcurl.def"))
        }
    }

    tasks.named("detekt") {
        enabled = false
    }

    tasks.named("ktlint") {
        enabled = false
    }
}
