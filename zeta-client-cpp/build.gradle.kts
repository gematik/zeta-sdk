import de.gematik.zeta.sdk.buildlogic.isLinuxEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.isNativeEnabled
import de.gematik.zeta.sdk.buildlogic.isWindowsEnabled
import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    kotlin("multiplatform")
    id("cpp-application")
}

repositories {
    mavenLocal()
    mavenCentral()
}

val libraryName = "hello"

kotlin {
    if (project.isNativeEnabled && project.isMacOSEnabled) {
        macosArm64 {
            val main by compilations.getting
            val interop by main.cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/interop.def"))
            }
            binaries {
                sharedLib {
                    baseName = libraryName
                }
            }
        }
    }
    if (project.isNativeEnabled && project.isLinuxEnabled) {
        linuxX64 {
            val main by compilations.getting
            val interop by main.cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/interop.def"))
            }
            binaries {
                sharedLib {
                    baseName = libraryName
                }
            }
        }
    }
    if (project.isNativeEnabled && project.isWindowsEnabled) {
        mingwX64 {
            val main by compilations.getting
            val interop by main.cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/interop.def"))
            }
            binaries {
                sharedLib {
                    baseName = libraryName
                }
            }
        }
    }
    sourceSets.nativeMain.dependencies {
        implementation(project(":zeta-sdk"))
    }
}


application {
    binaries.configureEach {
        val platform = targetPlatform()
        val buildType = name.replace("main", "")

        tasks.withType<LinkExecutable> {
            linkerArgs.addAll(
                listOf(
                    "-L$projectDir/build/bin/$platform/debugShared",
                    "-l$libraryName",
                    "-Wl,-rpath,@executable_path",
                    "-Wl,-rpath,$projectDir/build/bin/$platform/debugShared"
                )
            )
            dependsOn("linkDebugShared${platform.capitalized()}")
        }

        tasks.register<Exec>("run$buildType") {
            environment("PATH", "$projectDir/build/bin/$platform/debugShared" + ";" + System.getenv("PATH"))
            commandLine("$projectDir/build/exe/main/debug/zeta-client-cpp")
            dependsOn("assemble$buildType")
        }
    }
}

fun CppBinary.targetPlatform(): String {
    val tm = targetMachine

    val os = when {
        tm.operatingSystemFamily.isMacOs -> "macos"
        tm.operatingSystemFamily.isWindows -> "mingw"
        tm.operatingSystemFamily.isLinux -> "linux"
        else -> tm.operatingSystemFamily.name
    }

    val arch = when (tm.architecture.name) {
        "aarch64" -> "arm64"
        "x86-64" -> "x64"
        else -> tm.architecture.name
    }

    val platform = os + arch.capitalized()

    return platform
}
