import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":network"))
            implementation(project(":storage"))
            implementation(libs.ktor.client.core)
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }

    dependencies {
        implementation(project(":network"))
        implementation(project(":storage"))
    }
}
