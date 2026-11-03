import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.gradle.kotlin.dsl.invoke

plugins {
    id("de.gematik.zeta.sdk.build-logic.app")
    id("de.gematik.zeta.sdk.build-logic.kmp")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        if (project.isAndroidEnabled) {
            sourceSets.androidMain.dependencies {
                implementation(project(":zeta-sdk"))
                implementation(project(":zeta-testdriver-core"))
                api(libs.ktor.client.android)
                api(libs.androidx.core.ktx)
                api(libs.androidx.activity)
                api(libs.androidx.appcompat)
                api(libs.androidx.activity.compose)
                api(libs.compose.material3)
                implementation(libs.androidx.browser)
                implementation(libs.firebase.messaging.ktx)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.kotlinx.serialization.json)
            }
        }
    }
}

tasks.withType<Test> {
    filter {
        failOnNoDiscoveredTests = false
    }
}
