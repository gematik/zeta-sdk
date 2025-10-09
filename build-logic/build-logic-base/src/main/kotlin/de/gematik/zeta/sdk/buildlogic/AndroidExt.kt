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

import com.android.build.gradle.BaseExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

fun Project.setupAndroid(
    coreLibraryDesugaring: Provider<MinimalExternalModuleDependency>?,
    javaVersion: JavaVersion = JavaVersion.VERSION_17,
) {
    configure<BaseExtension> {
        namespace = getDefaultPackageName()
        val minSdkVersion = rootLibs.findVersion("minSdk").get().toString().toInt()
        val targetSdkVersion = rootLibs.findVersion("targetSdk").get().toString().toInt()
        compileSdkVersion(rootLibs.findVersion("compileSdk").get().toString().toInt())
        defaultConfig {
            minSdk = minSdkVersion
            targetSdk = targetSdkVersion
            versionCode = 1
            versionName = project.version as String
            // Required for coreLibraryDesugaring
            multiDexEnabled = true
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            isCoreLibraryDesugaringEnabled = coreLibraryDesugaring != null
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }

        testOptions {
            // Needed for Robolectric
            unitTests {
                isIncludeAndroidResources = true
            }
        }

        packagingOptions {
            resources {
                pickFirsts.add("META-INF/LICENSE-notice.md")
                pickFirsts.add("META-INF/LICENSE.md")
                pickFirsts.add("META-INF/*.kotlin_module")
                pickFirsts.add("META-INF/AL2.0")
                pickFirsts.add("META-INF/LGPL2.1")
                pickFirsts.add("META-INF/**/MANIFEST.MF")
            }
        }
    }
    if (coreLibraryDesugaring != null) {
        dependencies {
            add("coreLibraryDesugaring", coreLibraryDesugaring)
        }
    }
}
