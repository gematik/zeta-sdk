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

@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

package de.gematik.zeta.sdk.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyBuilder
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

fun Project.setupKmp(
    javaVersion: JavaVersion = JavaVersion.VERSION_17,
    block: KotlinMultiplatformExtension.() -> Unit,
) {
    val commonMainDir = file("src/commonMain")
    if (!commonMainDir.exists() || commonMainDir.walkBottomUp().none { it.extension == "kt" }) {
        val packageName = getDefaultPackageName()
        withGeneratedBuildFile("empty", "${packageName.replace(".", "/")}/empty.kt", "commonMain") {
            """
            package $packageName

            // The Kotlin compiler doesn't like empty binaries
            // Workaround for https://youtrack.jetbrains.com/issue/KT-42702
            // and https://youtrack.jetbrains.com/issue/KT-47345
            internal val empty: Boolean = false
            """
        }
    }

    tasks.withType<Test> {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    configure<KotlinMultiplatformExtension> {
        explicitApi = ExplicitApiMode.Strict
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        compilerOptions {
            allWarningsAsErrors.set(true)
            optIn.addAll(commonKotlinOptIns)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        applyKmpHierarchy()
        block()
        tasks.withType<KotlinNativeCompile> {
            compilerOptions {
                optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        targets.withType<KotlinMetadataTarget> {
            compilerOptions.allWarningsAsErrors.set(false)
        }
    }
}

val commonKotlinOptIns = listOf(
    "kotlin.RequiresOptIn",
    "kotlin.ExperimentalStdlibApi",
    "kotlin.ExperimentalUnsignedTypes",
    "kotlin.concurrent.atomics.ExperimentalAtomicApi",
    "kotlin.contracts.ExperimentalContracts",
    "kotlin.experimental.ExperimentalObjCRefinement",
    "kotlin.io.encoding.ExperimentalEncodingApi",
    "kotlin.time.ExperimentalTime",
    "kotlin.uuid.ExperimentalUuidApi",
)

fun KotlinMultiplatformExtension.applyKmpHierarchy(block: KotlinHierarchyBuilder.Root.() -> Unit = {}) {
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withAndroidTarget()
            }
            group("desktop") {
                withLinux()
                withMingw()
                withMacos()
            }
            group("appleMobile") {
                group("ios")
                withIos()
                withTvos()
                withWatchos()
            }
            group("mobile") {
                withAndroidTarget()
                group("ios")
                withIos()
                withTvos()
                withWatchos()
            }
            group("compose") {
                withJs()
                withWasmJs()
                withWasmWasi()
                group("ios")
                withIos()
                withJvm()
                withAndroidTarget()
            }
            group("nonJvm") {
                withNative()
                withJs()
                withWasmJs()
            }
            group("nonJs") {
                withNative()
                withJvm()
                withAndroidTarget()
            }
            group("jsCommon") {
                group("js")
                withJs()
                group("wasmJs")
                withWasmJs()
            }
        }
        block()
    }
}

fun KotlinMultiplatformExtension.addAllTargets(
    onlyComposeSupport: Boolean = false,
    iosX64: Boolean = true,
) {
    if (project.isAndroidEnabled) {
        androidTarget {
            publishLibraryVariants("release")
        }
        if (!onlyComposeSupport) {
            allAndroidNative()
        }
    }
    jvm()
    allJs()
    if (project.isIOSEnabled) {
        allAppleMobile(x64 = iosX64, onlyComposeSupport = onlyComposeSupport)
    }
    if (project.isNativeEnabled) {
        allDesktop()
    }
}

fun KotlinMultiplatformExtension.allDesktop() {
    allMacos()
    allLinux()
    mingwX64()
}

fun KotlinMultiplatformExtension.allLinux() {
    linuxX64()
    linuxArm64()
}

fun KotlinMultiplatformExtension.allMacos() {
    macosArm64()
    macosX64()
}

fun KotlinMultiplatformExtension.allAndroidNative() {
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()
}

fun KotlinMultiplatformExtension.allAppleMobile(x64: Boolean = true, onlyComposeSupport: Boolean) {
    allIos(x64 = x64)
    allTvos()
    allWatchos(onlyComposeSupport = onlyComposeSupport)
}

fun KotlinMultiplatformExtension.allIos(x64: Boolean = true, simulatorArm64: Boolean = true) {
    val targets = System.getenv("IOS_TARGETS")?.takeIf { it.isNotBlank() }?.split(",")
        ?: listOfNotNull("arm64", "simulatorArm64".takeIf { simulatorArm64 }, "x64".takeIf { x64 })
    iosArm64()
    if ("simulatorArm64" in targets) {
        iosSimulatorArm64()
    }
    if ("x64" in targets) {
        iosX64()
    }
}

fun KotlinMultiplatformExtension.allTvos() {
    tvosArm64()
    tvosX64()
    tvosSimulatorArm64()
}

fun KotlinMultiplatformExtension.allWatchos(onlyComposeSupport: Boolean) {
    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    if (!onlyComposeSupport) {
        watchosDeviceArm64()
    }
}

fun KotlinMultiplatformExtension.allJs() {
    js(IR) {
        browser()
        nodejs()
    }

    wasmJs {
        browser()
        nodejs()
    }
}
