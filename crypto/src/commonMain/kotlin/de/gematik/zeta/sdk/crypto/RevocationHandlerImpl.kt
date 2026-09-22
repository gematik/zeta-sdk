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

package de.gematik.zeta.sdk.crypto

import kotlin.time.Instant

interface RevocationHandler {
    fun getOcspValidity(ocspResponseDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray): OcspValidity
    fun validate(ocspResponseDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray, now: Instant)
    suspend fun prepareOcspRequest(certDer: ByteArray, issuerDer: ByteArray): OcspRequestData
    fun extractCrlUrl(certDer: ByteArray): String?
    fun validateCrl(crlDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray, now: Instant)

    /** Reads thisUpdate and nextUpdate of [crlDer] in one pass over the DER. */
    fun getCrlValidity(crlDer: ByteArray): CrlValidity
}

expect class RevocationHandlerImpl() : RevocationHandler {
    override fun getOcspValidity(ocspResponseDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray): OcspValidity
    override fun validate(ocspResponseDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray, now: Instant)
    override suspend fun prepareOcspRequest(certDer: ByteArray, issuerDer: ByteArray): OcspRequestData
    override fun extractCrlUrl(certDer: ByteArray): String?
    override fun validateCrl(crlDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray, now: Instant)
    override fun getCrlValidity(crlDer: ByteArray): CrlValidity
}

data class OcspRequestData(
    val url: String,
    val requestDer: ByteArray,
)

data class OcspValidity(
    val thisUpdateEpochSeconds: Long,
    val nextUpdateEpochSeconds: Long?,
)

data class CrlValidity(
    val thisUpdateEpochSeconds: Long,
    val nextUpdateEpochSeconds: Long?,
)
