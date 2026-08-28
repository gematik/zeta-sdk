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

package de.gematik.zeta.sdk.authentication.identity

import AsymAlg
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl.Companion.applyHtuRules
import de.gematik.zeta.sdk.authentication.AccessTokenUtility
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlin.io.encoding.Base64
import kotlin.time.Clock

internal const val IDENTITY_CLIENT_ASSERTION_LIFETIME_SECONDS = 30L
internal const val IDENTITY_CLIENT_ASSERTION_MAX_LIFETIME_SECONDS = 60L
internal const val IDENTITY_CLIENT_ASSERTION_TYP = "JWT"

internal class IdentityClientAssertionFactory(
    private val tpmProvider: TpmProvider,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
    private val lifetimeSeconds: Long = IDENTITY_CLIENT_ASSERTION_LIFETIME_SECONDS,
) {
    init {
        require(lifetimeSeconds in 1..IDENTITY_CLIENT_ASSERTION_MAX_LIFETIME_SECONDS) {
            "Client-Assertion lifetime must be between 1 and $IDENTITY_CLIENT_ASSERTION_MAX_LIFETIME_SECONDS seconds"
        }
    }

    suspend fun create(clientId: String, issuer: String, method: String, url: String): String {
        val now = clock()
        val jti = tpmProvider.randomUuid().toHexDashString()
        val kid = tpmProvider.getOrGenerateClientInstancePublicKey().jwk.kid
        val unsigned = AccessTokenUtility.create(
            IdentityClientAssertionHeader(
                alg = AsymAlg.ES256.name,
                typ = IDENTITY_CLIENT_ASSERTION_TYP,
                kid = kid,
            ),
            IdentityClientAssertionClaims(
                iss = clientId,
                sub = clientId,
                aud = issuer,
                jti = jti,
                iat = now,
                exp = now + lifetimeSeconds,
                htm = method,
                htu = url.applyHtuRules(),
            ),
        )
        val signature = tpmProvider.signWithClientKey(unsigned.encodeToByteArray())
        val signatureB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)
        return AccessTokenUtility.addSignature(unsigned, signatureB64)
    }
}
