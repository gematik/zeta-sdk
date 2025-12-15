import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        explicitApi = ExplicitApiMode.Disabled

        sourceSets.commonMain.dependencies {
            implementation(project(":common"))
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }

        sourceSets["jvmCommonMain"].dependencies {
            api(libs.bcprov.jdk18on)
        }

        sourceSets.commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
