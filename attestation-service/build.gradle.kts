import de.gematik.zeta.sdk.buildlogic.isLinuxEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":common"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.serialization.protobuf)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.kotlinx.serialization.json)
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        if (project.isNativeEnabled) {
            sourceSets["desktopMain"].dependencies {
                implementation(libs.okio)
                implementation(libs.kfswatch)
            }
        }

        if (project.isMacOSEnabled) {
            macosArm64 {
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.sdk.attestation.main"
                        baseName = "zeta-attestation-service"
                    }
                }
            }
        }

        if (project.isLinuxEnabled) {
            linuxX64 {
                compilations.getByName("main") {
                    cinterops {
                        val tpm by creating {
                            defFile(project.file("src/linuxMain/cinterop/tpm.def"))
                            packageName("tpm")
                        }
                    }
                }
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.sdk.attestation.main"
                        baseName = "zeta-attestation-service"
                    }
                }
            }
        }
    }
}
