import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            api(project(":zeta-sdk"))
            api(libs.ktor.server.core)
        }
    }
}

tasks.withType<Test> {
    filter {
        failOnNoDiscoveredTests = false
    }
}
