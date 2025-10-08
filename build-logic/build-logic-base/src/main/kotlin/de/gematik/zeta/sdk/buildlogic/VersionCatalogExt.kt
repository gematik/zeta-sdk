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
import org.gradle.api.plugins.catalog.CatalogPluginExtension
import org.gradle.kotlin.dsl.configure

fun Project.setupVersionCatalog(normalizeAlias: (Project) -> String) {
    // Workaround for https://github.com/gradle/gradle/issues/33568
    gradle.taskGraph.whenReady {
        configure<CatalogPluginExtension> {
            versionCatalog {
                version("kotlin", rootLibs.findVersion("kotlin").get().toString())
                val versionAlias = version(rootProject.name.lowercase(), project.version.toString())
                for (subproject in rootProject.subprojects) {
                    if (!subproject.plugins.hasPlugin("maven-publish") ||
                        subproject.plugins.hasPlugin("version-catalog")
                    ) {
                        continue
                    }
                    library(
                        normalizeAlias(subproject),
                        subproject.group.toString(),
                        subproject.name,
                    ).versionRef(versionAlias)
                }
            }
        }
    }
}
