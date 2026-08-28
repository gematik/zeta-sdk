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

package de.gematik.zeta.sdk.attestation.model

import de.gematik.zeta.logging.Log
import de.gematik.zeta.platform.getPlatformInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import platform.UIKit.UIDevice

actual suspend fun buildPosture(
    platformProductId: PlatformProductId?,
    productId: String,
    productVersion: String,
    attChallenge: String,
    publicKeyB64: String,
): JsonElement {
    Log.d { "Building posture. Getting platform information" }
    val platformInfo = getPlatformInfo()
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    return json.encodeToJsonElement(
        SoftwarePosture(
            productId = productId,
            productVersion = productVersion,
            platformProductId = platformProductId,
            os = platformInfo.os,
            osVersion = platformInfo.osVersion,
            arch = platformInfo.arch,
            publicKey = publicKeyB64,
            attestationChallenge = attChallenge,
        ),
    )
}

actual suspend fun getPlatform(): Platform = Platform.APPLE
actual fun getPostureType(): PostureType = PostureType.SOFTWARE
