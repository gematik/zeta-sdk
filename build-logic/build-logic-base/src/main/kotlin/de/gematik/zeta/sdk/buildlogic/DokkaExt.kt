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
import org.gradle.internal.extensions.core.extra
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters
import java.time.LocalDate

fun Project.setupDokka(copyright: String) {
    extra.set(dokkaDoneMarker, true)

    if (!isRootProject) {
        if (!rootProject.extra.has(dokkaDoneMarker)) {
            rootProject.setupDokka(copyright = copyright)
        }
        rootProject.dependencies {
            add("dokka", this@setupDokka)
        }
    }
    configure<DokkaExtension> {
        dokkaSourceSets.configureEach {
            enableAndroidDocumentationLink.set(true)
            includes.from(
                *fileTree(projectDir) {
                    includes.addAll(listOf("index.md", "README.md", "Module.md", "docs/*.md"))
                }.files.toTypedArray(),
            )
        }
        pluginsConfiguration.withType<DokkaHtmlPluginParameters> {
            footerMessage.set("Copyright © ${LocalDate.now().year} $copyright")
        }
        if (isRootProject) {
            dokkaPublications.configureEach {
                includes.from(rootProject.file("docs/README.md"))
                outputDirectory.set(rootProject.file("build/docs/$name"))
            }
        }
    }
}

private val dokkaDoneMarker = "_dokka_setup_done"
