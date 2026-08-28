import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
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
            api(project(":common"))
            api(project(":crypto"))
            api(libs.ktor.serialisation)
            implementation(libs.kstore)
            implementation(libs.kstore.file)
            implementation(libs.okio)
            implementation(libs.multiplatform.settings)
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmMain.dependencies {
                implementation(libs.multiplatform.settings.jvm)
                implementation(libs.java.keyring)
            }
        }

        if (project.isJvmEnabled){
            sourceSets.jvmTest.dependencies {
                implementation(libs.mockk)
            }
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.multiplatform.settings.test)
            implementation(libs.coroutines.test)
        }
    }
}
