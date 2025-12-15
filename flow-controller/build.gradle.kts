import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
}

setupBuildLogic {

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            api(project(":network"))
            api(project(":configuration"))
            api(project(":client-registration"))
            api(project(":authentication"))
            api(project(":tpm"))
            api(project(":storage"))
            api(project(":asl"))
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
