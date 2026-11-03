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
                implementation(project(":zeta-testdriver-core"))
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.core.jvm)
                implementation(libs.ktor.server.netty.jvm)
                implementation(libs.ktor.server.cors.jvm)
                implementation(libs.ktor.server.websockets.jvm)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.appium.java.client)
                implementation(libs.cucumber.java)
                implementation(libs.cucumber.core)
                implementation(libs.selenium.support)
                implementation(libs.jackson.datatype.jdk8)
                testImplementation(libs.jackson.databind)
                implementation(libs.netty.codec.http) {
                    version {
                        // fixing CVE-2026-42587, CVE-2026-33870 and CVE-2026-3387, CVE-2026-59901
                        strictly("4.2.19.Final")
                    }
                }
                implementation(libs.netty.codec.http2) {
                    version {
                        // fixing CVE-2026-42587, CVE-2026-33870 and CVE-2026-3387, CVE-2026-59901
                        // fixing CVE-2026-47244
                        strictly("4.2.19.Final")
                    }
                }
                implementation(libs.netty.codec.compression) {
                    version {
                        // fixing CVE-2026-59901
                        strictly("4.2.19.Final")
                    }
                }
                implementation(libs.netty.transport.native.epoll) {
                    version {
                        // fixing CVE-2026-42587
                        // fixing CVE-2026-45536
                        strictly("4.2.19.Final")
                    }
                }
                implementation(libs.netty.transport.native.kqueue) {
                    version {
                        // fixing CVE-2026-45536
                        strictly("4.2.19.Final")
                    }
                }
                implementation(libs.netty.handler) {
                    version {
                        // fixing CVE-2026-44249, CVE-2026-45416
                        strictly("4.2.19.Final")
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
    group = "build"
    description = "Copies runtime dependencies to build/runtime-libs"
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("build/runtime-libs"))
}
