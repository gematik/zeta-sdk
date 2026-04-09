import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.isIOSEnabled
import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.sharedlib")
    id("org.jetbrains.kotlin.plugin.serialization")
}

setupBuildLogic {
    kotlin {
        tasks.withType<Test> {
            failOnNoDiscoveredTests = false
        }
        sourceSets.commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.reactivestate.core)
            api(libs.serialization.core)
            api(libs.serialization.json)
        }

        if (project.isAndroidEnabled) {
            sourceSets.androidMain.dependencies {
                api(libs.androidx.startup)
            }
        }

        if (project.isJvmEnabled) {
            sourceSets["jvmCommonMain"].dependencies {
                implementation(libs.logger.slf4j.simple)
            }
        }

        if (project.isAndroidEnabled) {
            kotlin.sourceSets.getByName("androidMain").dependencies {
                implementation(libs.logger.napier)
            }
        }

        if (project.isIOSEnabled) {
            kotlin.sourceSets.getByName("iosMain").dependencies {
                implementation(libs.logger.napier)
            }
        }

        tasks.withType<Test> {
            failOnNoDiscoveredTests = false
        }
    }
}
