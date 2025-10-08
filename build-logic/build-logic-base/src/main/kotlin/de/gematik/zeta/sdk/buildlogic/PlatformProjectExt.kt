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
import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType

fun Project.setupPlatformProject() {
    extensions.getByType<JavaPlatformExtension>().allowDependencies()

    afterEvaluate {
        val allPublications = rootProject.subprojects.flatMap { subproject ->
            if (!subproject.plugins.hasPlugin("maven-publish") ||
                subproject.plugins.hasPlugin("java-platform") ||
                subproject.plugins.hasPlugin("version-catalog")
            ) {
                return@flatMap emptyList()
            }
            subproject.extensions.findByType<PublishingExtension>()?.publications.orEmpty()
                .filterIsInstance<MavenPublication>()
                .filterNot {
                    it.artifactId.endsWith("-metadata") || it.artifactId.endsWith("-kotlinMultiplatform")
                }.map {
                    subproject.dependencies.constraints.create("${it.groupId}:${it.artifactId}:${it.version}")
                }
        }

        configurations.named("api").get().apply {
            dependencyConstraints.addAll(allPublications)
        }
    }
}
