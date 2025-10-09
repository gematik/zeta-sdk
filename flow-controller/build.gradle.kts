import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
}

setupBuildLogic {
    android {
        packaging {
            resources {
                excludes += "META-INF/LICENSE.md"
                excludes += "META-INF/LICENSE-notice.md"
                excludes += "META-INF/LICENSE"
                excludes += "META-INF/NOTICE"
                excludes += "META-INF/NOTICE.txt"
            }
        }
    }

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            api(project(":network"))
            api(project(":configuration"))
            api(project(":client-registration"))
            api(project(":authentication"))
            api(project(":tpm"))
            api(project(":storage"))
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
        implementation(project(":client-registration"))
        implementation(project(":storage"))
        implementation(project(":authentication"))
        implementation(project(":tpm"))
    }
}
