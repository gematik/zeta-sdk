import com.android.build.gradle.BaseExtension
import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.isIOSEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("de.gematik.zeta.sdk.build-logic.app")
    id("de.gematik.zeta.sdk.build-logic.compose")
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.xcframework")
    id("co.touchlab.skie")
    kotlin("plugin.serialization")
    alias(libs.plugins.googleServices) apply false
}

// Optional push-notification feature (Firebase Cloud Messaging), disabled by default.
// Enable by setting a `ZETA_GOOGLE_SERVICES_JSON=` line in the runtime config file
// `src/androidMain/assets/zeta.env` (the same file the app reads via AndroidConfig)
// pointing at a valid google-services.json. See the "Push notifications (optional)"
// section in the README for the required setup (Firebase project, permissions).
// Parsing mirrors AndroidConfig.init(): skip blank/`#` lines, split on the first `=`,
// trim key and value.
val googleServicesJson = layout.projectDirectory.file("src/androidMain/assets/zeta.env").asFile
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstNotNullOfOrNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@firstNotNullOfOrNull null
        val (key, value) = line.split("=", limit = 2).takeIf { it.size == 2 }
            ?: return@firstNotNullOfOrNull null
        if (key.trim() == "ZETA_GOOGLE_SERVICES_JSON") value.trim().ifBlank { null } else null
    }
    ?.let(::file)
    ?.also { if (!it.isFile) logger.warn("ZETA_GOOGLE_SERVICES_JSON (from zeta.env) points to a non-existent file: $it — push notifications stay disabled.") }
    ?.takeIf { it.isFile }

val notificationsEnabled = project.isAndroidEnabled && googleServicesJson != null

if (notificationsEnabled) {
    // The google-services plugin expects the file in the module directory; copy it there
    // from the location given by ZETA_GOOGLE_SERVICES_JSON (the copy is git-ignored).
    val pluginTarget = layout.projectDirectory.file("google-services.json").asFile
    if (!pluginTarget.exists() || !pluginTarget.readBytes().contentEquals(googleServicesJson!!.readBytes())) {
        googleServicesJson!!.copyTo(pluginTarget, overwrite = true)
    }
    pluginManager.apply("com.google.gms.google-services")

    // Swap in the notification-specific manifest and add the notification resources.
    // These override/extend the base Android source set only when the feature is enabled.
    configure<BaseExtension> {
        sourceSets.getByName("main") {
            manifest.srcFile("src/androidNotifications/AndroidManifest.xml")
            res.srcDir("src/androidNotifications/res")
        }
    }
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            implementation(project(":common"))
            api(libs.ktor.client.logging)
            api(libs.ktor.kotlinx.serialization.json)
            api(libs.coroutines.core)
            api(compose.runtime)
            api(compose.material3)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.reactivestate.compose)
            api(libs.okio)
            implementation(libs.ktor.server.cio)
            api(project(":zeta-sdk"))
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmMain.dependencies {
                api(libs.coroutines.swing)
                api(compose.desktop.currentOs)
            }
        }

        if (project.isIOSEnabled) {
            sourceSets.iosMain.dependencies {
                api(libs.ktor.client.darwin)
            }
        }

        if (project.isAndroidEnabled) {
            sourceSets.androidMain.dependencies {
                api(libs.ktor.client.android)
                api(libs.androidx.core.ktx)
                api(libs.androidx.activity)
                api(libs.androidx.appcompat)
                api(libs.androidx.activity.compose)
                api(compose.material3)
                implementation(libs.androidx.browser)
                if (notificationsEnabled) {
                    implementation(libs.firebase.messaging.ktx)
                }
            }
            // Exactly one variant of `createNotificationIntegration()` is compiled in:
            // the Firebase-backed one when the feature is enabled, a no-op otherwise.
            sourceSets.androidMain {
                kotlin.srcDir(
                    if (notificationsEnabled) {
                        "src/androidNotifications/kotlin"
                    } else {
                        "src/androidNoNotifications/kotlin"
                    },
                )
            }
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.mockk)
                implementation(libs.reactivestate.core.test)
            }
        }

        if (project.isMacOSEnabled) {
            macosArm64 {
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.client.main"
                        baseName = "zeta-attestation-service"
                        linkerOpts += listOf("-framework", "Security", "-framework", "CoreFoundation")
                    }
                }
            }
        }
    }

    if (project.isJvmEnabled) {
        compose.desktop {
            application {
                mainClass = "de.gematik.zeta.client.ui.ZetaClientApp"

                nativeDistributions {
                    packageName = "Zero Sample"
                    packageVersion = "1.0.0"

                    targetFormats(
                        TargetFormat.Dmg,
                        TargetFormat.Exe,
                        TargetFormat.Deb,
                    )
                }
            }
        }
    }
}
