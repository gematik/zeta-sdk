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
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.russhwolf:multiplatform-settings:1.3.0")
        }

        sourceSets.jvmMain.dependencies {
            implementation("com.russhwolf:multiplatform-settings-jvm:1.3.0")
            implementation(libs.java.keyring)
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }

}
