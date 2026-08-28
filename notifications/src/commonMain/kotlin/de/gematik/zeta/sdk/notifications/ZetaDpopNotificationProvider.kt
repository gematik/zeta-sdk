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

package de.gematik.zeta.sdk.notifications

import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.tpm.TpmProvider

/**
 * Target [NotificationDpopProvider]: builds the proof through zeta-sdk's [AccessTokenProvider]
 * with a TPM-backed DPoP key, mirroring how `flow-controller` and `asl` sign resource-server
 * requests — the `ath` claim carries the base64url-encoded SHA-256 hash of the access token.
 */
class ZetaDpopNotificationProvider(
    private val accessTokenProvider: AccessTokenProvider,
    private val tpmProvider: TpmProvider,
) : NotificationDpopProvider {
    override suspend fun createDpopProof(htm: String, htu: String, accessToken: String): String {
        val accessTokenHash = accessTokenProvider.hash(accessToken)
        val dpopKey = tpmProvider.generateDpopKey()
        return accessTokenProvider.createDpopToken(dpopKey.jwk, htm, htu, null, accessTokenHash)
    }
}
