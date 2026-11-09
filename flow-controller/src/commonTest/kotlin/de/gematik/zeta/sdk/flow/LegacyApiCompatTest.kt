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

package de.gematik.zeta.sdk.flow

import de.gematik.zeta.sdk.clientregistration.model.AppleAttestation
import de.gematik.zeta.sdk.clientregistration.model.ClientMetadata
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.clientregistration.model.SoftwareAttestation
import de.gematik.zeta.sdk.clientregistration.model.toJson
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyApiCompatTest {

    private val metadata = ClientMetadata(
        tokenEndpointAuthMethod = "private_key_jwt",
        grantTypes = listOf("refresh_token"),
        clientName = "client",
        jwks = Jwks(emptyList()),
    )

    private fun version(major: Int, status: ApiVersionStatus = ApiVersionStatus.STABLE) =
        ApiVersion(majorVersion = major, version = "$major.0.0", status = status)

    @Test
    fun select_onlyV1_isV1() = assertEquals(LegacyApiCompat.V1, LegacyApiCompat.select(listOf(version(1))))

    @Test
    fun select_v1AndV2_needsNoAdaptation() = assertNull(LegacyApiCompat.select(listOf(version(1), version(2))))

    @Test
    fun select_onlyV2_needsNoAdaptation() = assertNull(LegacyApiCompat.select(listOf(version(2))))

    @Test
    fun select_unknownNewerVersion_needsNoAdaptation() = assertNull(LegacyApiCompat.select(listOf(version(3))))

    @Test
    fun select_retiredV2_ignoredSoV1Applies() =
        assertEquals(LegacyApiCompat.V1, LegacyApiCompat.select(listOf(version(1), version(2, ApiVersionStatus.RETIRED))))

    @Test
    fun everyEntryHasUniqueMajor() =
        assertEquals(LegacyApiCompat.entries.size, LegacyApiCompat.entries.map { it.major }.toSet().size)

    @Test
    fun v1_registrationBody_dropsProofOfPossessionFields() {
        val request = ClientRegistrationRequest.Software(
            metadata,
            SoftwareAttestation(nonce = "nonce", signedHashPukClientSig = "sig"),
        )

        val body = LegacyApiCompat.V1.registrationBody(request)

        assertFalse("nonce" in body)
        assertFalse("signed_hash_puk_client_sig" in body)
        assertFalse("attestation_type" in body)
        assertTrue("client_name" in body)
        assertTrue("jwks" in body)
    }

    @Test
    fun v1_omitsApplePlatformProductIdInSoftwarePosture() =
        assertTrue(LegacyApiCompat.V1.omitsApplePlatformProductIdInSoftwarePosture)

    @Test
    fun select_emptyList_isLegacyV1() = assertEquals(LegacyApiCompat.V1, LegacyApiCompat.select(emptyList()))

    @Test
    fun select_onlyRetired_isLegacyV1() =
        assertEquals(LegacyApiCompat.V1, LegacyApiCompat.select(listOf(version(1, ApiVersionStatus.RETIRED))))

    @Test
    fun omitsApplePlatformProductId_onlyForV1Servers() {
        assertTrue(omitsApplePlatformProductIdInSoftwarePosture(null))
        assertTrue(omitsApplePlatformProductIdInSoftwarePosture(listOf(version(1))))
        assertFalse(omitsApplePlatformProductIdInSoftwarePosture(listOf(version(2))))
        assertFalse(omitsApplePlatformProductIdInSoftwarePosture(listOf(version(3))))
    }

    @Test
    fun v1_registrationBody_keepsHardwareRequestsUnchanged() {
        val request = ClientRegistrationRequest.Apple(
            metadata,
            AppleAttestation(appleAttestationObject = "obj", signedHashPukClientSig = "sig"),
        )

        assertEquals(request.toJson(), LegacyApiCompat.V1.registrationBody(request))
    }
}
