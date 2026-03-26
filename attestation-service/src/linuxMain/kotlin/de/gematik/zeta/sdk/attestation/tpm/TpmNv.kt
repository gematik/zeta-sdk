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

import cnames.structs.ESYS_CONTEXT
import de.gematik.zeta.logging.Log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import tpm.ESYS_TRVar
import tpm.ESYS_TR_NONE
import tpm.ESYS_TR_PASSWORD
import tpm.ESYS_TR_RH_OWNER
import tpm.Esys_Free
import tpm.Esys_NV_Read
import tpm.Esys_NV_ReadPublic
import tpm.Esys_TR_FromTPMPublic
import tpm.TPM2B_MAX_NV_BUFFER
import tpm.TPM2B_NV_PUBLIC
import tpm.TSS2_RC_SUCCESS

@OptIn(ExperimentalForeignApi::class)
fun MemScope.readNvCert(
    esys: CPointer<ESYS_CONTEXT>,
    nvIndex: UInt,
): ByteArray? {
    val nvHandle = alloc<ESYS_TRVar>()
    val rcTr = Esys_TR_FromTPMPublic(
        esys,
        nvIndex,
        ESYS_TR_NONE,
        ESYS_TR_NONE,
        ESYS_TR_NONE,
        nvHandle.ptr,
    )
    if (rcTr != TSS2_RC_SUCCESS) {
        Log.w { "[EK-chain] NV index 0x${nvIndex.toString(16)} not found (rc=0x${rcTr.toString(16)})" }
        return null
    }

    val nvPublicPtr = alloc<CPointerVar<TPM2B_NV_PUBLIC>>()
    val rcPub = Esys_NV_ReadPublic(
        esys,
        nvHandle.value,
        ESYS_TR_NONE,
        ESYS_TR_NONE,
        ESYS_TR_NONE,
        nvPublicPtr.ptr,
        null,
    )
    if (rcPub != TSS2_RC_SUCCESS) {
        Log.w { "[EK-chain] NV_ReadPublic failed at 0x${nvIndex.toString(16)} (rc=0x${rcPub.toString(16)})" }
        closeEsysHandle(esys, nvHandle.value)
        return null
    }

    val declaredSize = nvPublicPtr.value!!.pointed.nvPublic.dataSize
    Esys_Free(nvPublicPtr.value)

    val dataPtr = alloc<CPointerVar<TPM2B_MAX_NV_BUFFER>>()
    val rcRead = Esys_NV_Read(
        esys,
        ESYS_TR_RH_OWNER,
        nvHandle.value,
        ESYS_TR_PASSWORD,
        ESYS_TR_NONE,
        ESYS_TR_NONE,
        declaredSize,
        0u,
        dataPtr.ptr,
    )

    closeEsysHandle(esys, nvHandle.value)

    if (rcRead != TSS2_RC_SUCCESS) {
        Log.w { "[EK-chain] NV_Read failed at 0x${nvIndex.toString(16)} (rc=0x${rcRead.toString(16)})" }
        if (dataPtr.value != null) Esys_Free(dataPtr.value)
        return null
    }

    val buf = dataPtr.value!!.pointed
    val cert = buf.buffer.readBytes(buf.size.toInt())
    Esys_Free(dataPtr.value)

    Log.i { "[EK-chain] Read ${cert.size} bytes from NV 0x${nvIndex.toString(16)}" }
    return cert
}
