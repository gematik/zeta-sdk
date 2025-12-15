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
    sourceSets.nativeMain.dependencies {
        implementation(project(":zeta-sdk"))
    }
}

tasks.withType<LinkExecutable>().configureEach {
    linkerArgs.addAll(
        listOf(
            "-L$buildDir/bin/macosArm64/debugShared",
            "-l$libraryName",
            "-Wl,-rpath,@executable_path"
        )
    )
    dependsOn("linkDebugSharedMacosArm64")
}

tasks.register<Exec>("runDebug") {
    commandLine("$buildDir/exe/main/debug/zeta-client-cpp")
    dependsOn("assembleDebug")
}
