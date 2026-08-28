import com.ensody.nativebuilds.cinterops
import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.isIOSEnabled
import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    kotlin("plugin.serialization")
    alias(libs.plugins.nativebuilds)
}

setupBuildLogic {
    kotlin {
        targets.withType<KotlinMetadataTarget> {
            compilerOptions.allWarningsAsErrors.set(false)
        }

        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":common"))
            implementation(libs.cryptography.core)
        }

        if (project.isJvmEnabled || project.isAndroidEnabled) {
            sourceSets["jvmCommonMain"].dependencies {
                api(libs.bcprov.jdk18on)
                api(libs.bcpkix.jdk18on)
                implementation(libs.cryptography.provider.jdk)
            }
        }

        if (project.isIOSEnabled) {
            sourceSets.iosMain.dependencies {
                implementation(libs.cryptography.provider.cryptokit)
                api(libs.nativebuilds.openssl.libcrypto)
            }
        }

        if (project.isNativeEnabled) {
            sourceSets["desktopMain"].dependencies {
                implementation(libs.cryptography.provider.openssl3.api)
                api(libs.nativebuilds.openssl.libcrypto)
                api(libs.okio)
            }
        }

        sourceSets["jvmCommonTest"].dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        cinterops(libs.nativebuilds.openssl.headers) {
            definitionFile.set(file("src/desktopMain/cinterop/openssl.def"))
        }
    }
}
