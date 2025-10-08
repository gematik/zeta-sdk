/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

@file:Suppress("UnstableApiUsage")

package de.gematik.zeta.sdk.buildlogic

import com.android.build.gradle.internal.cxx.io.writeTextIfDifferent
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// NOTE: The following plugins get registered based on their class name prefix as de.gematik.zeta.sdk.build-logic.<prefix>

/** BOM setup. */
class BomBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("java-platform")
            }
        }
    }
}

/** Version catalog setup. */
class VersionCatalogBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("version-catalog")
            }
        }
    }
}

/** Maven publication setup. */
class PublishBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("maven-publish")
                pluginManager.apply("com.vanniktech.maven.publish")
            }
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.dokka")
        }
    }
}

/** Dokka setup. */
class DokkaBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            pluginManager.apply("org.jetbrains.dokka")
        }
    }
}

/** Shared Kotlin setup. */
class KotlinBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("io.gitlab.arturbosch.detekt")
            }
        }
    }
}

/** KMP setup. */
class KmpBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                if (!pluginManager.hasPlugin("com.android.application")) {
                    pluginManager.apply("com.android.library")
                }
                pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            }
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.kotlin")
        }
    }
}

/** Cocoapods setup. */
class CocoapodsBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.kmp")
            if (!isRootProject) {
                pluginManager.apply("org.jetbrains.kotlin.native.cocoapods")
            }
        }
    }
}

/** XCFramework setup. */
class XcFrameworkBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.cocoapods")
            if (!isRootProject) {
                pluginManager.apply("co.touchlab.skie")
            }
        }
    }
}

/** Jetpack Compose setup. */
class ComposeBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("org.jetbrains.compose")
                pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            }
        }
    }
}

/** Android app setup. */
class AppBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("com.android.application")
            }
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.compose")
        }
    }
}

/** JVM setup. */
class JvmBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            if (!isRootProject) {
                pluginManager.apply("org.jetbrains.kotlin.jvm")
            }
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.kotlin")
        }
    }
}

/** Gradle plugin setup. */
class GradleBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.run {
            pluginManager.apply("de.gematik.zeta.sdk.build-logic.jvm")
            if (!isRootProject) {
                pluginManager.apply("java-gradle-plugin")
            }
        }
    }
}

class BuildLogicBaseDependencies(
    val rootProject: Project,
) {
    val libs: VersionCatalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
}

lateinit var buildLogicBaseDeps: BuildLogicBaseDependencies

fun Project.initBuildLogicBase(block: Project.() -> Unit) {
    require(isRootProject) { "initBuildLogic() must be called on the root project!" }
    buildLogicBaseDeps = BuildLogicBaseDependencies(this)
    version = detectProjectVersion()
    println("Version: $version")
    subprojects {
        version = rootProject.version
    }

    // Setup detekt.yml
    val rules = BuildLogicBaseDependencies::class.java.module.getResourceAsStream("detekt.yml").reader().readText()
    file("build/build-logic/detekt.yml").writeTextIfDifferent(rules)

    block()
}

fun Project.setupBuildLogicBase(block: Project.() -> Unit) {
    group = (listOf(rootProject.group) + project.path.trimStart(':').split(".").dropLast(1))
        .joinToString(".")
    block()
    afterEvaluate {
        val generatedRoot = getGeneratedBuildFilesRoot()
        val generatedRelativePaths = generatedFiles[this.path].orEmpty().flatMap { it.relativeTo(generatedRoot).withParents().map { it.toString() } }.toSet()
        for (file in generatedRoot.walkBottomUp()) {
            if (file == generatedRoot) continue
            if (file.relativeTo(generatedRoot).toString() !in generatedRelativePaths) {
                file.deleteRecursively()
            }
        }
    }
}

val rootLibs get() = buildLogicBaseDeps.libs
