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

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

package de.gematik.zeta.sdk.buildlogic

import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.kotlin.dsl.assign
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import com.vanniktech.maven.publish.MavenPublishBaseExtension

fun MavenPomLicenseSpec.apache2() {
    license {
        name = "The Apache Software License, Version 2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    }
}

fun MavenPomLicenseSpec.mit() {
    license {
        name = "The MIT License"
        url = "https://opensource.org/licenses/mit-license.php"
    }
}

fun MavenPublishBaseExtension.prepareForMavenCentralPublishing(project: Project) {
    pom {
        //        coordinates(
        //            groupId = "de.gematik.zeta",
        //            artifactId = "zeta-sdk",
        //            version = "0.0.1-SNAPSHOT"
        //        )

        name.set("ZETA Client")
        description.set("Kotlin Multiplatform client library to access ZETA Guard API")
        url.set("https://github.com/gematik/zeta-sdk")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("gematik")
                name.set("gematik")
                email.set("software-development@gematik.de")
                url.set("https://gematik.github.io/")
                organization.set("gematik GmbH")
                organizationUrl.set("https://www.gematik.de/")
            }
        }

        scm {
            url.set("https://github.com/gematik/zeta-sdk")
            connection.set("scm:git:git@github.com:gematik/zeta-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com:gematik/zeta-sdk.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/gematik/zeta-sdk/issues")
        }

        organization {
            name.set("gematik GmbH")
            url.set("https://www.gematik.de")
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral()

    // Enable GPG signing for all publications
    signAllPublications()
}
