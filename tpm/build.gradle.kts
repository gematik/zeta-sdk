import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
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

        // Wire `desktopMain` to `nativeMain` so it can see the shared native crypto provider.
        sourceSets.named("desktopMain") {
            dependsOn(sourceSets.named("nativeMain").get())
        }

        sourceSets.named("desktopTest") {
            dependsOn(sourceSets.named("nativeTest").get())
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmTest.dependencies {
                implementation(libs.mockk)
            }
        }
    }
}
