import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.gradle.kotlin.dsl.sourceSets

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets {
            val commonMain by getting {
                dependencies {
                    implementation(project(":network"))
                    implementation(project(":tpm"))
                    implementation(project(":storage"))
                    implementation(project(":crypto"))
                    implementation(project(":authentication"))
                    implementation(libs.serialization.cbor)
                }
            }

            val commonTest by getting {
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.coroutines.test)
                    implementation(libs.ktor.client.mock)
                }
            }
        }
    }
}
