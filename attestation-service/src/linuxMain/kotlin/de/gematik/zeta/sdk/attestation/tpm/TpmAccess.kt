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
@file:Suppress("ktlint:standard:no-wildcard-imports", "WildcardImport")
package de.gematik.zeta.sdk.attestation.tpm

import de.gematik.zeta.sdk.attestation.model.TpmQuoteResult
import de.gematik.zeta.sdk.attestation.service.TpmException
import kotlinx.cinterop.*
import tpm.*

actual class TpmAccess actual constructor() {

    @OptIn(ExperimentalForeignApi::class)
    actual fun isAvailable(): Boolean = memScoped {
        val tctiContext = alloc<CPointerVar<TSS2_TCTI_CONTEXT>>()
        val initResult = Tss2_TctiLdr_Initialize(null, tctiContext.ptr)

        if (initResult != TSS2_RC_SUCCESS) {
            return@memScoped false
        }

        Tss2_TctiLdr_Finalize(tctiContext.ptr)
        true
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun generateQuote(
        attChallengeBytes: ByteArray,
        pcrSelection: List<Int>,
    ): TpmQuoteResult = memScoped {
        withTpmContext { tpmContext ->
            val attestationKeyPublic = ensureAkPersistent(tpmContext.esys, AK_PERSISTENT_HANDLE)

            val (quotedData, quotedSignature) = withPersistentHandle(
                esys = tpmContext.esys,
                persistentHandle = AK_PERSISTENT_HANDLE,
            ) { attestationKeyHandle ->
                setEmptyAuth(tpmContext.esys, attestationKeyHandle)

                val qualifyingData = allocQualifyingData(attChallengeBytes)
                val pcrSelectionList = buildPcrSelection(pcrSelection)

                createQuote(tpmContext, qualifyingData, pcrSelectionList, attestationKeyHandle)
            }

            TpmQuoteResult(
                quote = quotedData,
                signature = quotedSignature,
                attestationKey = attestationKeyPublic,
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun readPCRs(pcrSelection: List<Int>): Map<Int, ByteArray> = memScoped {
        require(pcrSelection.isNotEmpty()) { "PCR selection must not be empty" }

        withTpmContext { tpmContext ->
            val pcrSelectionList = buildPcrSelection(pcrSelection)
            val updateCounter = alloc<UIntVar>()
            val selectionOutput = alloc<CPointerVar<TPML_PCR_SELECTION>>()
            val digestValues = alloc<CPointerVar<TPML_DIGEST>>()

            val readResult = Esys_PCR_Read(
                tpmContext.esys,
                ESYS_TR_NONE,
                ESYS_TR_NONE,
                ESYS_TR_NONE,
                pcrSelectionList.ptr,
                updateCounter.ptr,
                selectionOutput.ptr,
                digestValues.ptr,
            )

            if (readResult != TSS2_RC_SUCCESS) {
                throw TpmException("Failed to read PCRs", readResult)
            }

            val extractedValues = extractPcrValues(digestValues.value, pcrSelection)

            Esys_Free(selectionOutput.value)
            Esys_Free(digestValues.value)

            extractedValues
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun extendPCR(pcrIndex: Int, data: ByteArray) = memScoped {
        requirePcrIndex(pcrIndex)
        require(data.size == SHA256_DIGEST_BYTES) {
            "Hash must be $SHA256_DIGEST_BYTES bytes for SHA256, received ${data.size}"
        }

        withTpmContext { tpmContext ->
            val digestValues = buildDigestValues(data)

            val extendResult = Esys_PCR_Extend(
                tpmContext.esys,
                pcrIndex.toUInt(),
                ESYS_TR_PASSWORD,
                ESYS_TR_NONE,
                ESYS_TR_NONE,
                digestValues.ptr,
            )

            if (extendResult != TSS2_RC_SUCCESS) {
                throw TpmException("Failed to extend PCR $pcrIndex", extendResult)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun resetPCR(pcrIndex: Int) = memScoped {
        requirePcrIndex(pcrIndex)

        withTpmContext { tpmContext ->
            val resetResult = Esys_PCR_Reset(
                tpmContext.esys,
                pcrIndex.toUInt(),
                ESYS_TR_PASSWORD,
                ESYS_TR_NONE,
                ESYS_TR_NONE,
            )

            if (resetResult != TSS2_RC_SUCCESS) {
                throw TpmException("Failed to reset PCR $pcrIndex", resetResult)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun provisionAttestationKey(): ByteArray = memScoped {
        withTpmContext { ctx ->
            ensureAkPersistent(ctx.esys, AK_PERSISTENT_HANDLE)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun removeAttestationKey() = memScoped {
        withTpmContext { tpmContext ->
            if (!hasPersistentHandle(tpmContext.esys, AK_PERSISTENT_HANDLE)) {
                return@withTpmContext
            }

            val attestationKeyHandle = loadPersistentAsEsys(tpmContext.esys, AK_PERSISTENT_HANDLE)

            try {
                evictAttestationKey(tpmContext.esys, attestationKeyHandle)
            } finally {
                closeEsysHandle(tpmContext.esys, attestationKeyHandle)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun MemScope.evictAttestationKey(
        esysContext: CPointer<cnames.structs.ESYS_CONTEXT>,
        attestationKeyHandle: ESYS_TR,
    ) {
        val evictedHandle = alloc<ESYS_TRVar>()

        val evictResult = Esys_EvictControl(
            esysContext,
            ESYS_TR_RH_OWNER,
            attestationKeyHandle,
            ESYS_TR_PASSWORD,
            ESYS_TR_NONE,
            ESYS_TR_NONE,
            AK_PERSISTENT_HANDLE,
            evictedHandle.ptr,
        )

        if (evictResult != TSS2_RC_SUCCESS) {
            val handleHex = AK_PERSISTENT_HANDLE.toString(16)
            throw TpmException("Failed to evict attestation key at 0x$handleHex", evictResult)
        }
    }

    actual fun getEventLog(): ByteArray = readTpmEventLog()

    @OptIn(ExperimentalForeignApi::class)
    actual fun getEKCertificateChain(): List<ByteArray> = memScoped {
        withTpmContext { ctx ->
            val certs = mutableListOf<ByteArray>()

            val ekCert = readNvCert(ctx.esys, NV_EK_CERT_ECC)
            if (ekCert != null) certs.add(ekCert)

            val platformCert = readNvCert(ctx.esys, NV_PLATFORM_CERT)
            if (platformCert != null) certs.add(platformCert)

            val attestationCert = readNvCert(ctx.esys, NV_ATTESTATION_CERT)
            if (attestationCert != null) certs.add(attestationCert)

            certs
        }
    }
}
