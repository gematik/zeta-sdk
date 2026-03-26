import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.isLinuxEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.isWindowsEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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

        if (project.isJvmEnabled) {
            sourceSets.jvmTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.ktor.client.mock)
            }
        }

        val nativeIncludeDir = "-I${project.projectDir}/src/nativeInterop/include"
        val interopDefFile = project.file("src/nativeInterop/cinterop/interop.def")

        fun KotlinNativeTarget.configureInterop() {
            val main by compilations.getting
            val interop by main.cinterops.creating {
                definitionFile.set(interopDefFile)
                compilerOpts(nativeIncludeDir)
            }
        }

        if (project.isNativeEnabled) {
            if (project.isMacOSEnabled) {
                macosX64 { configureInterop() }
                macosArm64 { configureInterop() }
            }
            if (project.isLinuxEnabled) linuxX64 { configureInterop() }
            if (project.isWindowsEnabled) mingwX64 { configureInterop() }
        }

        afterEvaluate {
            val zetaSdkHeader = file("src/nativeInterop/include/zeta_sdk_types.h").readText()
            kotlin.targets
                .filterIsInstance<KotlinNativeTarget>()
                .flatMap { it.binaries.filter { b -> b.name.contains("Shared") } }
                .forEach { binary ->
                    tasks.findByName(binary.linkTaskName)?.doLast {
                        val header = binary.outputDirectory.listFiles()
                            ?.firstOrNull { it.name.endsWith("_api.h") }
                            ?: return@doLast

                        header.writeText(header.readText().replaceFirst("#ifdef __cplusplus", "$zetaSdkHeader\n#ifdef __cplusplus"))
                    }

                    val patchTaskName = "patchHeader${binary.linkTaskName.removePrefix("link")}"
                    tasks.register(patchTaskName) {
                        group = "build"
                        description = "Patch generated C header with ZetaSdk types for ${binary.name} (${binary.target.name})"
                        dependsOn(binary.linkTaskName)
                    }
                }
        }
    }
}
