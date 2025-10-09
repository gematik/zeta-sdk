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

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.withType

fun Project.setupDetekt(
    javaVersion: JavaVersion = JavaVersion.VERSION_17,
) {
    tasks.withType<Detekt>().configureEach {
        // Enable type resolution
        classpath = detektClasspath
        jvmTarget = javaVersion.majorVersion

        config.from(rootProject.file("build/build-logic/detekt.yml"))
        buildUponDefaultConfig = true

        setSource(
            files(
                file("src").listFiles().orEmpty().filter {
                    it.name.endsWith("Main") || it.name.endsWith("Test") || it.name in setOf("main", "test")
                }.flatMap {
                    listOf(it.resolve("kotlin"), it.resolve("java"))
                },
            ),
        )
    }
    tasks.withType<DetektCreateBaselineTask>().configureEach {
        jvmTarget = "17"
    }
}
