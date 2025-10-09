import de.gematik.zeta.sdk.buildlogic.setupBuildLogic

plugins {
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.publish")
    id("de.gematik.zeta.sdk.build-logic.xcframework")
    id("co.touchlab.skie")
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
        sourceSets.commonMain.dependencies {
            api(project(":common"))
            api(libs.ktor.client.core)
            api(libs.ktor.serialisation)
            api(libs.ktor.client.logging)
            api(libs.ktor.content.negotiation)
            api(libs.ktor.kotlinx.serializable)

        }
        sourceSets.androidMain.dependencies {
            api(libs.ktor.client.okhttp)
            implementation(libs.okhttp.tls)
        }

        sourceSets.jvmMain.dependencies {
            api(libs.ktor.client.okhttp)
            implementation(libs.okhttp.tls)
        }

        sourceSets.iosMain.dependencies {
            api(libs.ktor.client.darwin)
        }

        sourceSets.commonTest.dependencies {
            api(kotlin("test"))
            api(libs.ktor.client.mock)
            api(libs.coroutines.test)
        }

        sourceSets.androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.okhttp.tls)
        }

        sourceSets.jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.okhttp.tls)
        }
    }
}
