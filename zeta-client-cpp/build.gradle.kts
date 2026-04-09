import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    kotlin("multiplatform")
    id("cpp-application")
}

kotlin {
    mingwX64()
}

repositories {
    mavenLocal()
    mavenCentral()
}

val sdkProject = project(":zeta-sdk")
val sdkBuildDir = "${sdkProject.projectDir}/build"

application {
    binaries.configureEach {
        val platform = targetPlatform()
        val buildType = name.replace("main", "")
        val sdkLibDir = "$sdkBuildDir/bin/$platform/${buildType.lowercase()}Shared"

        tasks.withType<CppCompile>().configureEach {
            if (name.contains(buildType, ignoreCase = true)) {
                dependsOn(":zeta-sdk:link${buildType}Shared${platform.capitalized()}")
                compilerArgs.addAll(listOf("-I$sdkLibDir"))
            }
        }

        tasks.withType<LinkExecutable> {
            if (name.contains(buildType, ignoreCase = true)) {
                linkerArgs.addAll(listOf(
                    "-L$sdkLibDir",
                    "-lzeta_sdk",
                    "-Wl,-rpath,@executable_path",
                    "-Wl,-rpath,$sdkLibDir"
                ))
                dependsOn(":zeta-sdk:link${buildType}Shared${platform.capitalized()}")
            }
        }

        tasks.register<Exec>("run$buildType") {
            group = "run"
            description = "Run the C++ client ($buildType)"
            val tm = targetMachine
            when {
                tm.operatingSystemFamily.isMacOs -> environment("DYLD_LIBRARY_PATH", sdkLibDir)
                tm.operatingSystemFamily.isLinux -> environment("LD_LIBRARY_PATH", sdkLibDir)
                tm.operatingSystemFamily.isWindows -> environment("PATH", "$sdkLibDir;${System.getenv("PATH")}")
            }
            commandLine("$projectDir/build/exe/main/${buildType.lowercase()}/zeta-client-cpp")
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
    return os + arch.capitalized()
}
