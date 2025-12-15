import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.xcframework")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    id("co.touchlab.skie")
}

setupBuildLogic {

    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            api(project(":flow-controller"))
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }

}
