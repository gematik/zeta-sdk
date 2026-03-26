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

package de.gematik.zeta.sdk.attestation.tpm

import kotlinx.cinterop.ExperimentalForeignApi
import tpm.TPM2_ALG_ECDSA
import tpm.TPM2_ALG_SHA256
import tpm.TPM2_ECC_NIST_P256

internal const val PCR_MIN_INDEX: Int = 0
internal const val PCR_MAX_INDEX: Int = 23
internal const val PCR_SELECTION_BYTES: UByte = 3u
internal const val MAX_QUALIFYING_DATA_BYTES: Int = 64
internal const val SHA256_DIGEST_BYTES: Int = 32
internal const val PERSISTENT_HANDLE_MIN: UInt = 0x81000000u
internal const val PERSISTENT_HANDLE_MAX: UInt = 0x81FFFFFFu
internal const val PRIMARY_OBJECT_ATTRIBUTES: UInt = 0x00030472u
internal const val ATTESTATION_OBJECT_ATTRIBUTES: UInt = 0x00050072u

@OptIn(ExperimentalForeignApi::class)
internal const val ECC_CURVE: UShort = TPM2_ECC_NIST_P256

@OptIn(ExperimentalForeignApi::class)
internal const val AK_SIG_SCHEME: UShort = TPM2_ALG_ECDSA

@OptIn(ExperimentalForeignApi::class)
internal const val HASH_ALG: UShort = TPM2_ALG_SHA256

internal const val AK_PERSISTENT_HANDLE: UInt = 0x81000001u

internal fun requirePcrIndex(pcrIndex: Int) =
    require(pcrIndex in PCR_MIN_INDEX..PCR_MAX_INDEX) { "PCR index must be between $PCR_MIN_INDEX and $PCR_MAX_INDEX" }

internal const val NV_EK_CERT_ECC: UInt = 0x01C0000Au
internal const val NV_PLATFORM_CERT: UInt = 0x01C00001u
internal const val NV_ATTESTATION_CERT: UInt = 0x01C00004u
