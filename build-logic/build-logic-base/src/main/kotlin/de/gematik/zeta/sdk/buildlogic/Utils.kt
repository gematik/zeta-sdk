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

package de.gematik.zeta.sdk.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

val Project.isRootProject get() = this == rootProject

val Project.isAndroidEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableAndroid") == "true"

val Project.isIOSEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableIOS") == "true"

val Project.isNativeEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableNative") == "true"

val Project.isWindowsEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableWindows") == "true"

val Project.isLinuxEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableLinux") == "true"

val Project.isMacOSEnabled get() = findProperty("de.gematik.zeta.sdk.build-logic.enableMacOS") == "true"

fun shell(
    command: String,
    workingDir: File? = null,
    env: Map<String, String> = emptyMap(),
    inheritIO: Boolean = false,
): String {
    val processBuilder = ProcessBuilder("/bin/bash", "-c", command)
    workingDir?.let { processBuilder.directory(it) }
    processBuilder.redirectErrorStream(true)
    processBuilder.environment().putAll(env)
    val process = processBuilder.start()
    return process.inputStream.bufferedReader().readText().trim().also {
        val exitCode = process.waitFor()
        if (inheritIO) {
            println(it)
        }
        check(exitCode == 0) { "Process exit code was: $exitCode\nOriginal command: $command" }
    }
}

fun Project.withGeneratedBuildFile(category: String, path: String, sourceSet: String? = null, content: () -> String) {
    val generatedDir = file("${getGeneratedBuildFilesRoot()}/$category")
    extensions.findByType<KotlinJvmExtension>()?.apply {
        sourceSets[sourceSet ?: "main"].kotlin.srcDir(generatedDir)
    } ?: extensions.findByType<KotlinMultiplatformExtension>()?.apply {
        sourceSets[sourceSet ?: "commonMain"].kotlin.srcDir(generatedDir)
    } ?: error("Don't know how to add generated build file because project ${this.path} has unknown type")
    val outputPath = file("$generatedDir/$path")
    outputPath.writeTextIfDifferent(content().trimIndent().trimStart() + "\n")
    generatedFiles.getOrPut(this.path) { mutableSetOf() }.add(outputPath.normalize().absoluteFile)
}

fun Project.getDefaultPackageName(): String =
    group.toString().split(".").let { prefix ->
        prefix + name.split("-").dropWhile { it == prefix.last() }
    }.joinToString(".")

internal val generatedFiles = mutableMapOf<String, MutableSet<File>>()

internal fun File.withParents(): List<File> =
    buildList {
        add(this@withParents)
        while (true) {
            add(last().parentFile ?: break)
        }
    }

internal fun Project.getGeneratedBuildFilesRoot(): File =
    file("$projectDir/build/generated/source/build-logic")


internal fun Project.detectProjectVersion(): String =
    providers.environmentVariable("RELEASE_VERSION")
        .orElse(providers.environmentVariable("CI_COMMIT_TAG").map { it.removePrefix("v") })
        .orElse(providers.environmentVariable("CI_COMMIT_SHA").map { it.take(8) + "-SNAPSHOT"})
        .orElse( project.version.toString()).get()

private class VersionComparable(val parts: List<String>) : Comparable<VersionComparable> {
    override fun compareTo(other: VersionComparable): Int {
        for ((l, r) in parts.zip(other.parts)) {
            val result = l.compareTo(r)
            if (result != 0) return result
        }
        return parts.size.compareTo(other.parts.size)
    }
}

private fun sanitizeBranchName(name: String): String =
    sanitizeRegex.replace(name, "-")

private val versionRegex = Regex("""v-?(\d+)\.(\d+)\.(\d+)((-.+?\.)(\d+))*""")
private val sanitizeRegex = Regex("""[^A-Za-z0-9\-]""")

fun File.containsExactText(text : String) = isFile && text == readText()

fun File.writeTextIfDifferent(text : String) : Boolean {
    if (containsExactText(text)) return false
    parentFile.mkdirs()
    writeText(text)
    return true
}
