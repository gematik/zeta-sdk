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

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Verifies [ZetaDpopNotificationProvider] assembles the proof exactly like the other
 * resource-server callers (`flow-controller`, `asl`): TPM-provided DPoP key, `ath` = hash of the
 * access token, no nonce, and the request's method/URL passed through unchanged.
 */
class ZetaDpopNotificationProviderTest {
    private class FakeAccessTokenProvider : AccessTokenProvider {
        var seenJwk: Jwk? = null
        var seenMethod: String? = null
        var seenUrl: String? = null
        var seenNonce: ByteArray? = byteArrayOf(1) // sentinel: must be overwritten with null
        var seenAccessTokenHash: String? = null

        override suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, dpopKey: String): String =
            error("not used")

        override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
            seenJwk = dpopKey
            seenMethod = method
            seenUrl = url
            seenNonce = nonceBytes
            seenAccessTokenHash = accessTokenHash
            return "signed-proof"
        }

        override suspend fun hash(token: String): String = "hash($token)"
    }

    private class FakeTpmProvider(private val dpopKey: PublicKeyOut) : TpmProvider {
        override suspend fun generateDpopKey(): PublicKeyOut = dpopKey

        override suspend fun isHardwareBacked(): Boolean = error("not used")
        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut = error("not used")
        override suspend fun signWithClientKey(input: ByteArray): ByteArray = error("not used")
        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray = error("not used")
        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray = error("not used")
        override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray = error("not used")
        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray = error("not used")
        override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray =
            error("not used")
        override suspend fun randomUuid(): Uuid = error("not used")
        override suspend fun getRegistrationNumber(certificate: ByteArray): String = error("not used")
        override suspend fun forget(resource: String?) = error("not used")
    }

    @Test
    fun createDpopProof_signsProofWithTpmKeyBoundToRequestAndTokenHash() = runTest {
        val jwk = Jwk(kid = "kid-1", kty = "EC", alg = "ES256", use = "sig", crv = "P-256", x = "x", y = "y")
        val accessTokenProvider = FakeAccessTokenProvider()
        val provider = ZetaDpopNotificationProvider(accessTokenProvider, FakeTpmProvider(PublicKeyOut(byteArrayOf(), jwk)))

        val proof = provider.createDpopProof("GET", "https://notification-service.example/pushers", "access-token")

        assertEquals("signed-proof", proof)
        assertEquals(jwk, accessTokenProvider.seenJwk)
        assertEquals("GET", accessTokenProvider.seenMethod)
        assertEquals("https://notification-service.example/pushers", accessTokenProvider.seenUrl)
        assertNull(accessTokenProvider.seenNonce, "resource-server proofs carry no nonce")
        assertEquals("hash(access-token)", accessTokenProvider.seenAccessTokenHash)
    }
}
