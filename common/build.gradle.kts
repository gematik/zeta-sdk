import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            api(libs.coroutines.core)
            implementation(libs.logger.napier)
            api(libs.reactivestate.core)
            api(libs.serialization.core)
            api(libs.serialization.json)
        }
        sourceSets.androidMain.dependencies {
            api(libs.androidx.startup)
        }
    }
    dependencies {
        api(platform(libs.reactivestate.bom))
    }
}
