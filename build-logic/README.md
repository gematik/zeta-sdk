# Build logic

This folder contains our reusable build scripts. The code is based on [ReactiveState's build-logic](https://github.com/ensody/ReactiveState-Kotlin/tree/main/build-logic).

## Structure

In `build-logic-base` we have project-independent code that could theoretically be reused in multiple repositories.

In `build-logic` we have project-specific code. That module defines multiple Gradle Plugins, so in each `build.gradle.kts` file we can just apply a high-level plugin and automatically get the correct configuration for e.g. backend or KMP modules.

## KMP modules

```
plugins {
    // This automatically adds AGP, KGP, Dokka, Detekt, Ktlint etc.
    id("de.gematik.zeta.sdk.build-logic.kmp")
    // If Compose is used in this module
    id("de.gematik.zeta.sdk.build-logic.compose")
    // If this module should be published (including documentation generation)
    id("de.gematik.zeta.sdk.build-logic.publish")
}

setupBuildLogic {
    // Add project configurations here instead of at the root
    kotlin {
        sourceSets.commonMain.dependencies {
            // ...
        }
        sourceSets.commonTest.dependencies {
            // ...
        }
    }
}
```

## JVM/backend modules

```
plugins {
    // This automatically adds KGP, Detekt, Ktlint etc.
    id("de.gematik.zeta.sdk.build-logic.jvm")
    // If this module should be published (including documentation generation)
    id("de.gematik.zeta.sdk.build-logic.publish")
}

setupBuildLogic {
    // Add project configurations here instead of at the root
    dependencies {
        // ...
    }
}
```
