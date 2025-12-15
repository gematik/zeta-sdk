import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    kotlin("plugin.serialization")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
}

setupBuildLogic {

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":common"))
            implementation(project(":storage"))
            implementation(project(":crypto"))
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        sourceSets.getByName("jvmCommonTest").dependencies {
            implementation(libs.mockk)
        }
    }
}
