import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {
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
        }
    }

    dependencies {
        api(project(":zeta-sdk"))
    }
}

version=""
var copyBuild = tasks.register<Copy>("copyRuntimeLibs"){
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("build/runtime-libs"))
}
