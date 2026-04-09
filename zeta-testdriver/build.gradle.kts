import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {
    if (project.isJvmEnabled) {
        kotlin {
            dependencies {
                implementation(project(":zeta-sdk"))
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.core.jvm)
                implementation(libs.ktor.server.netty.jvm)
                implementation(libs.ktor.server.logging.jvm)
                implementation(libs.ktor.server.cors.jvm)
                implementation(libs.ktor.server.websockets.jvm)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)

                implementation(libs.netty.codec.http) {
                    version {
                        // fixing CVE-2026-33870 and CVE-2026-3387
                        strictly("4.2.11.Final")
                    }
                }
                implementation(libs.netty.codec.http2) {
                    version {
                        // fixing CVE-2026-33870 and CVE-2026-3387
                        strictly("4.2.11.Final")
                    }
                }
            }
        }

        dependencies {
            api(project(":zeta-sdk"))
        }
    }
}

version=""
var copyBuild = tasks.register<Copy>("copyRuntimeLibs"){
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("build/runtime-libs"))
}
