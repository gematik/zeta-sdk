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
import de.gematik.zeta.platform.platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

actual suspend fun getPlatform(): Platform {
    when (platform()) {
        is de.gematik.zeta.platform.Platform.Native.Macos -> return Platform.APPLE
        is de.gematik.zeta.platform.Platform.Native.Linux -> return Platform.LINUX
        is de.gematik.zeta.platform.Platform.Native.Windows -> return Platform.WINDOWS
        else -> error("unknown platform")
    }
}

actual suspend fun buildPosture(
    productId: String,
    productVersion: String,
    attChallenge: String,
    publicKeyB64: String,
): JsonElement {
    Log.d { "Building posture. Getting platform information" }
    val platformInfo = getPlatformInfo()

    return Json.encodeToJsonElement(
        SoftwarePosture(
            productId = productId,
            productVersion = productVersion,
            platformProductId = platformProductId(),
            os = platformInfo.os,
            osVersion = platformInfo.osVersion,
            arch = platformInfo.arch,
            publicKey = publicKeyB64,
            attestationChallenge = attChallenge,
        ),
    )
}

// TODO
fun platformProductId(): JsonElement = Json.encodeToJsonElement("")
