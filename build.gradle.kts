import de.gematik.zeta.sdk.buildlogic.initBuildLogic

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("de.gematik.zeta.sdk.build-logic.base")
    id("de.gematik.zeta.sdk.build-logic.dokka")

    alias(libs.plugins.dependencyCheck)
}

dependencyCheck {
}

version="0.1.0"

initBuildLogic()
