import de.gematik.zeta.sdk.buildlogic.initBuildLogic

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("de.gematik.zeta.sdk.build-logic.base")
    id("de.gematik.zeta.sdk.build-logic.dokka")
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
    id("org.sonarqube") version "6.3.1.5724"
    id("org.cyclonedx.bom") version "3.0.1"

    alias(libs.plugins.dependencyCheck)
}

dependencies {
    // aggregate code coverage from subprojects
    kover(project(":attestation"))
    kover(project(":authentication"))
    kover(project(":client-registration"))
    kover(project(":common"))
    kover(project(":configuration"))
    kover(project(":flow-controller"))
    kover(project(":network"))
    kover(project(":storage"))
    kover(project(":tpm"))
    kover(project(":zeta-client"))
    kover(project(":zeta-client-java"))
    kover(project(":zeta-sdk"))
    kover(project(":zeta-testdriver"))
}

sonar {
    val jacocoPaths =
        subprojects.joinToString(", ") { subproject ->
            when (subproject.name) {
                // Exclude following modules from Jacoco coverage scan
                "app", "snapshot_testing", "hello_world_plugin" -> ""
                else -> "$rootDir/${subproject.name}/build/reports/kover/report.xml"
            }
        }

    properties {
        // mandatory for monorepos
        property("sonar.projectKey", "zeta_zeta-client_zeta-sdk_5b6b9b82-d91d-4748-afaa-3f5bcbfb0d8a")
        property("sonar.projectName", "zeta-sdk")
        //property("sonar.coverage.jacoco.xmlReportPaths", jacocoPaths)
        property("sonar.coverage.jacoco.xmlReportPaths", "$rootDir/build/reports/kover/report.xml")
        property("sonar.dependencyCheck.jsonReportPath","$rootDir/build/reports/dependency-check-report.json")
        property("sonar.dependencyCheck.htmlReportPath","$rootDir/build/reports/dependency-check-report.html")
    }
}

dependencyCheck {
    analyzers.ossIndex.enabled = false
    formats = listOf("HTML", "JSON")
}

subprojects {
    sonar {
        properties {
            property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
            property("sonar.kotlin.ktlint.reportPaths", "build/ktlint.xml")
        }
    }
}

dependencyCheck {
}

version = providers.environmentVariable("RELEASE_VERSION").orElse("latest").get()


initBuildLogic()
