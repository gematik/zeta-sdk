import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            api(project(":zeta-sdk"))
        }
    }
}

tasks.withType<Test> {
    filter {
        failOnNoDiscoveredTests = false
    }
}
