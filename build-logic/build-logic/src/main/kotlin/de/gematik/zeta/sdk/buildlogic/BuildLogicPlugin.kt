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

import com.android.build.gradle.BaseExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.plugins.catalog.CatalogPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.withType
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests

class BaseBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) {}
}

fun Project.initBuildLogic() {
    group = "de.gematik.zeta"

    initBuildLogicBase {
        setupRepositories()
    }
}

fun Project.setupRepositories() {
    repositories {
        google()
        mavenCentral()
        if (!isRunningOnCi) {
            mavenLocal()
        }
    }
}

fun Project.setupBuildLogic(block: Project.() -> Unit) {
    setupBuildLogicBase {
        val assemblePullRequest = tasks.register("assemblePullRequest") {
            group = "build"
            dependsOn("assembleShared")
        }.get()
        val assemblePublication = tasks.register("assemblePublication") {
            group = "build"
            dependsOn("assembleShared")
        }.get()
        val assembleShared = tasks.register("assembleShared") {
            group = "build"
        }.get()
        val testAll = tasks.register("testAll") {
            group = "verification"
        }.get()
        setupRepositories()
        if (extensions.findByType<JavaPlatformExtension>() != null) {
            setupPlatformProject()
        }
        if (extensions.findByType<BaseExtension>() != null && project.isAndroidEnabled) {
            setupAndroid(coreLibraryDesugaring = rootLibs.findLibrary("desugarJdkLibs").get())
        }
        if (extensions.findByType<KotlinMultiplatformExtension>() != null) {
            val arch = System.getProperty("os.arch")
            val onArm64 = "aarch64" in arch || "arm64" in arch
            setupKmp {
                if (project.isAndroidEnabled) {
                    androidTarget()
                }
                jvm()
                // TODO: Disable x64 builds once we've migrated to arm64 runners
                if (project.isIOSEnabled) {
                    allIos(x64 = isRunningOnCi || !onArm64)
                }
                compilerOptions {
                    optIn.addAll(commonExtraKotlinOptIns)
                    if (pluginManager.hasPlugin("de.gematik.zeta.sdk.build-logic.compose")) {
                        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
                    }
                }
                if (pluginManager.hasPlugin("de.gematik.zeta.sdk.build-logic.xcframework") && project.isIOSEnabled) {
                    configure<CocoapodsExtension> {
                        name = XcFrameworkDefaultName
                        summary = XcFrameworkDefaultName
                        homepage = "https://www.gematik.de"
                        license = "For license refer to general ZETA documentation"

                        ios.deploymentTarget = "15.0"

                        framework {
                            baseName = XcFrameworkDefaultName
                            binaryOption("bundleVersion", project.version.toString().split("-")[0])
                        }
                    }
                }
                if (pluginManager.hasPlugin("de.gematik.zeta.sdk.build-logic.sharedlib") && project.isNativeEnabled) {
                    val configure = Action<KotlinNativeTargetWithHostTests> {
                        binaries {
                            staticLib {
                                baseName = project.name
                            }
                            sharedLib {
                                baseName = project.name
                            }
                        }
                    }
                    macosArm64(configure)
                    macosX64(configure)
                    linuxX64(configure)
                    mingwX64(configure)
                }
                sourceSets["jvmCommonTest"].dependencies {
                    junit4()
                }
            }
            assemblePullRequest.dependsOn(
                "assembleDebug",
            )
            assemblePublication.dependsOn(
                "assembleRelease",
            )
            assembleShared.dependsOn(
                "compileKotlinIosArm64",
                if (onArm64) "compileTestKotlinIosArm64" else "compileTestKotlinIosX64",
                "jvmJar"
            )
            testAll.dependsOn(
                "testDebugUnitTest",
                "jvmTest",
                if (onArm64) "iosSimulatorArm64Test" else "iosX64Test",
            )
        }
        if (extensions.findByType<KotlinJvmExtension>() != null) {
            setupKotlinJvm {
                compilerOptions {
                    optIn.addAll(commonExtraKotlinOptIns)
                }
                sourceSets["test"].dependencies {
                    commonTestDeps()
                }
            }
            tasks.withType<Test> {
                useJUnitPlatform()
            }

            assembleShared.dependsOn("assemble")
            testAll.dependsOn("test")
        }
        if (extensions.findByType<KotlinBaseExtension>() != null) {
            setupKtLint(rootLibs.findLibrary("ktlint-cli").get())
        }
        if (extensions.findByType<DetektExtension>() != null) {
            setupDetekt()
        }
        if (extensions.findByType<DokkaExtension>() != null) {
            setupDokka(copyright = "EY GmbH & Co. KG")
        }
        if (extensions.findByType<CatalogPluginExtension>() != null) {
            setupVersionCatalog { it.name }
        }

        extensions.findByType<PublishingExtension>()?.apply {
            addGitlabRepository(project)
        }

        extensions.findByType<MavenPublishBaseExtension>()?.apply {
            configureBasedOnAppliedPlugins(
                javadocJar = isRunningOnCi,
                sourcesJar = true,
            )
            prepareForMavenCentralPublishing(project)
        }

        extensions.findByType<DesktopExtension>()?.apply {
            application {
                nativeDistributions {
                    targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.AppImage)
                    packageVersion = packageVersion ?: project.version.toString()
                }
            }
        }
        block()
    }
}

fun KotlinDependencyHandler.commonTestDeps() {
    implementation(kotlin("test"))
}

fun KotlinDependencyHandler.junit4() {
    implementation(rootLibs.findLibrary("kotlin-test-junit").get())
    implementation(rootLibs.findLibrary("junit").get())
}

internal val commonExtraKotlinOptIns = emptyList<String>()

private const val XcFrameworkDefaultName = "ZeroTrustShared"

// IMPORTANT: This is duplicated in multiple files. When changing this code, update all files.
val isRunningOnCi: Boolean by lazy {
    System.getenv("RUNNING_ON_CI") == "true" || System.getenv("TF_BUILD") == "True"
}

fun PublishingExtension.addGitlabRepository(project: Project) {
    repositories {
        maven {
            url = project.uri(System.getenv("MAVEN_REPOSITORY_URL") ?: "http://undefined-maven-repo-url/")
            credentials {
                username = System.getenv("MAVEN_REPOSITORY_USERNAME")
                password = project.findProperty("gitLabDeployToken") as String? ?: System.getenv("MAVEN_REPOSITORY_PASSWORD")
            }
        }
    }
}
