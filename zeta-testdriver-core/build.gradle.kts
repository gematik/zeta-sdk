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
        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        sourceSets.jvmTest.dependencies {
            implementation(libs.bcpkix.jdk18on)
            implementation(libs.bcprov.jdk18on)
        }
    }
}

tasks.withType<Test> {
    filter {
        failOnNoDiscoveredTests = false
    }
}
