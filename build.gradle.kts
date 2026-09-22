import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import de.gematik.zeta.sdk.buildlogic.initBuildLogic

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("de.gematik.zeta.sdk.build-logic.base")
    id("de.gematik.zeta.sdk.build-logic.dokka")
    id("com.github.ben-manes.versions") version "0.61.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.sonarqube") version "7.5.0.8588"
    id("org.cyclonedx.bom") version "3.4.1"

    alias(libs.plugins.dependencyCheck)
}

dependencies {
    // aggregate code coverage from subprojects
    kover(project(":attestation"))
    kover(project(":attestation-service"))
    kover(project(":authentication"))
    kover(project(":client-registration"))
    kover(project(":common"))
    kover(project(":configuration"))
    kover(project(":flow-controller"))
    kover(project(":network"))
    kover(project(":notifications"))
    kover(project(":storage"))
    kover(project(":tpm"))
    kover(project(":zeta-sdk"))
    kover(project(":asl"))
    kover(project(":crypto"))
}

allprojects {
    tasks.cyclonedxDirectBom {
        includeConfigs = listOf("runtimeClasspath", "compileClasspath")
    }
}

sonar {
    properties {
        // mandatory for monorepos
        property("sonar.projectKey", "zeta_zeta-client_zeta-sdk_5b6b9b82-d91d-4748-afaa-3f5bcbfb0d8a")
        property("sonar.projectName", "zeta-sdk")
        property(
            "sonar.coverage.exclusions",
            listOf(
                "**/nativeMain/**",
                "**/desktopMain/**",
                "**/linuxMain/**",
                "**/mingwMain/**",
                "**/mingwX64Main/**",
                "**/macosMain/**",
                "**/androidMain/**",
                "**/iosMain/**",
                "**/appleMain/**",
                "attestation-service/**",
                "zeta-client/**",
                "zeta-client-java/**",
                "zeta-testdriver/**",
                "zeta-nativetestdriver/**",
            ).joinToString(",")
        )
        property("sonar.coverage.jacoco.xmlReportPaths", "$rootDir/build/reports/kover/report.xml")
        //property("sonar.dependencyCheck.jsonReportPath","$rootDir/build/reports/dependency-check-report.json")
        //property("sonar.dependencyCheck.htmlReportPath","$rootDir/build/reports/dependency-check-report.html")
    }
}

dependencyCheck {
    analyzers.ossIndex.enabled = false
    formats = listOf("HTML", "JSON")
    val apiKey = providers.environmentVariable("NVD_API_KEY")
    if (apiKey.isPresent) {
        nvd.apiKey = apiKey.get()
    }
}

subprojects {
    sonar {
        properties {
            property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
            property("sonar.kotlin.ktlint.reportPaths", "build/ktlint.xml")
        }
    }
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    filterConfigurations = Spec { configuration ->
        !configuration.name.let { name ->
            name.contains("test", ignoreCase = true) ||
                name.contains("cinterop", ignoreCase = true) ||
                name.contains("cInterop", ignoreCase = true)
        }
    }
}

version = providers.environmentVariable("RELEASE_VERSION").orElse(version.toString()).get()


initBuildLogic()
