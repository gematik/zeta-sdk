import java.io.File

pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("co.touchlab.skie") version "0.10.6"
    }
}

rootProject.name = "Zero Trust"

fun autoDetectModules(dir: File) {
    for (file in dir.listFiles()) {
        if (file.name in setOf("src", "build-logic", "build", "gradle", "docs") || file.name.startsWith(".")) {
            continue
        }
        if (file.isDirectory()) {
            if ("build.gradle.kts" in file.list()) {
                include(":" + file.relativeTo(rootDir).path.replace("/", ":"))
            } else {
                autoDetectModules(file)
            }
        }
    }
}

autoDetectModules(rootDir)

include("flow-controller")
include("logging")
include("authentication")
