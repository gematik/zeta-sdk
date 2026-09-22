import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
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
            named("commonMain") {
                dependencies {
                    implementation(project(":network"))
                    implementation(project(":tpm"))
                    implementation(project(":storage"))
                    implementation(project(":crypto"))
                    implementation(project(":authentication"))
                    implementation(libs.serialization.cbor)
                }
            }

            named("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.coroutines.test)
                    implementation(libs.ktor.client.mock)
                }
            }

            if (project.isJvmEnabled) {
                sourceSets.jvmTest.dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.okhttp.mockwebserver)
                    implementation(libs.okhttp.tls)
                    implementation(libs.mockk)
                }
            }
        }
    }
}
