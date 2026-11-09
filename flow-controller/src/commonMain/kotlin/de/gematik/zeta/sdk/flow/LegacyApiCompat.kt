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

import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.toJson
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import kotlinx.serialization.json.JsonObject

/** Changes for older ZETA API versions. The current version needs no entry */
internal enum class LegacyApiCompat(val major: Int) {
    /** ZETA API 1.3.x */
    V1(1) {
        override val omitsApplePlatformProductIdInSoftwarePosture = true

        override fun registrationBody(request: ClientRegistrationRequest): JsonObject = when (request) {
            is ClientRegistrationRequest.Software -> JsonObject(request.toJson() - V1_SOFTWARE_UNSUPPORTED_FIELDS)
            else -> request.toJson()
        }
    },
    ;

    /** The guard rejects an Apple platform_product_id in a software posture (ZETAP-1103). */
    abstract val omitsApplePlatformProductIdInSoftwarePosture: Boolean

    /** Registration request body for this version. */
    abstract fun registrationBody(request: ClientRegistrationRequest): JsonObject

    companion object {
        private val V1_SOFTWARE_UNSUPPORTED_FIELDS = setOf("nonce", "signed_hash_puk_client_sig", "attestation_type")

        /**
         * Changes for the server's highest supported version; null if none are needed.
         * Without a usable version (field missing, empty or all retired) the guard is treated as 1.x:
         * guards before 1.3 publish nothing, 1.3.x publishes [1.0.0], 2.x [1.0.0, 2.0.0].
         */
        fun select(offered: List<ApiVersion>?): LegacyApiCompat? {
            val serverMax = offered.orEmpty()
                .filter { it.status != ApiVersionStatus.RETIRED }
                .maxOfOrNull { it.majorVersion }
                ?: return V1
            return entries.firstOrNull { it.major == serverMax }
        }
    }
}

/** True if the guard rejects an Apple platform_product_id in a software posture. */
fun omitsApplePlatformProductIdInSoftwarePosture(apiVersions: List<ApiVersion>?): Boolean =
    LegacyApiCompat.select(apiVersions)?.omitsApplePlatformProductIdInSoftwarePosture ?: false
