import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.isIOSEnabled
import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    id("de.gematik.zeta.sdk.build-logic.xcframework")
    id("co.touchlab.skie")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            api(project(":common"))
            api(project(":crypto"))
            api(project(":storage"))
            api(libs.ktor.client.core)
            api(libs.ktor.serialisation)
            api(libs.ktor.client.logging)
            api(libs.ktor.client.websockets)
            api(libs.ktor.content.negotiation)
            api(libs.ktor.kotlinx.serialization.json)
            api(libs.ktor.kotlinx.serialization.xml)
        }

        sourceSets.commonTest.dependencies {
            api(kotlin("test"))
            api(libs.ktor.client.mock)
            api(libs.coroutines.test)
        }

        if (project.isJvmEnabled) {
            sourceSets["jvmCommonMain"].dependencies {
                api(libs.ktor.client.okhttp)
                implementation(libs.okhttp.tls)
            }
            sourceSets.jvmTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.okhttp.mockwebserver)
                implementation(libs.okhttp.tls)
                implementation(libs.mockk)
            }

            sourceSets["jvmCommonTest"].dependencies {
                implementation(kotlin("test"))
                implementation(libs.okhttp.mockwebserver)
                implementation(libs.okhttp.tls)
                implementation(libs.mockk)
            }
        }

        if (project.isIOSEnabled) {
            sourceSets.iosMain.dependencies {
                api(libs.ktor.client.darwin)
            }
        }

        if (project.isNativeEnabled) {
            sourceSets.getByName("desktopMain").dependencies {
                api(project(":ktor-client-curl"))
            }
        }

        if (project.isAndroidEnabled) {
            sourceSets.androidUnitTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.okhttp.mockwebserver)
                implementation(libs.okhttp.tls)
            }
        }
    }
}
