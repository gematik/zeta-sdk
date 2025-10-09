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

package de.gematik.zeta.sdk.clientregistration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.platform.getPlatformInfo
import de.gematik.zeta.sdk.clientregistration.model.SoftwarePosture
import de.gematik.zeta.sdk.tpm.getClientInstanceKeyBase64

interface PostureProvider {
    suspend fun buildSoftwarePosture(): SoftwarePosture
}

class PostureProviderImpl : PostureProvider {
    override suspend fun buildSoftwarePosture(): SoftwarePosture {
        Log.d { "Building posture. Getting platform information" }
        val platformInfo = getPlatformInfo()

        Log.d { "Getting client instant key" }
        // [A_27799](01): Generate client key pair Base64 DER encoded (Elliptic Curve-Es256)
        val publicKey = getClientInstanceKeyBase64()

        return SoftwarePosture(
            os = platformInfo.os,
            osVersion = platformInfo.osVersion,
            arch = platformInfo.arch,
            publicKey = publicKey,
            attestationChallenge = publicKey,
        )
    }
}
