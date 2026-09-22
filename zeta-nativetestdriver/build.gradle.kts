import de.gematik.zeta.sdk.buildlogic.isLinuxEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {
    kotlin {
        if (project.isLinuxEnabled) {
            linuxX64 {
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.nativedriver.main"
                        baseName = "native-server"
                    }
                }
            }
        }

        if (project.isMacOSEnabled) {
            macosArm64 {
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.nativedriver.main"
                        baseName = "native-server"
                    }
                }
            }
        }

        sourceSets {
            named("nativeMain") {
                dependencies {
                    implementation(project(":zeta-sdk"))
                    implementation(libs.ktor.server.cio)
                    implementation(libs.ktor.server.content.negotiation)
                    implementation(libs.ktor.kotlinx.serialization.json)
                }
            }
        }
    }
}

tasks.withType<Test> {
    filter {
        failOnNoDiscoveredTests = false
    }
}
