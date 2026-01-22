import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":network"))
            implementation(project(":storage"))
            implementation(libs.json.schema.validator)
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        if (project.isNativeEnabled) {
            sourceSets.getByName("desktopMain").dependencies {
                implementation("com.squareup.okio:okio:3.9.0")
            }
        }
    }

}
