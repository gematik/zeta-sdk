enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    versionCatalogs {
        create("rootLibs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic-root"

fun autoDetectModules(dir: File) {
    for (file in dir.listFiles()) {
        if (file.name in setOf("src", "build", "gradle", "docs") || file.name.startsWith(".")) {
            continue
        }
        if (file.isDirectory()) {
            val children = file.list()
            if ("settings.gradle.kts" in children) {
                continue
            }
            if ("build.gradle.kts" in children) {
                include(":" + file.relativeTo(rootDir).path.replace("/", ":").replace("\\", ":"))
            } else {
                autoDetectModules(file)
            }
        }
    }
}

autoDetectModules(rootDir)
