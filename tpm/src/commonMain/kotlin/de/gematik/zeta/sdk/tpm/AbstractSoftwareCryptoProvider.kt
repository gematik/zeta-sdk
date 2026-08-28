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

package de.gematik.zeta.sdk.tpm

import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.X509PemReader
import kotlin.uuid.Uuid

/**
 * Base class holding the platform-independent [TpmProvider] behaviour shared by
 * every software crypto provider (SM-B certificate handling, UUID generation and
 * key state clearing). Platform-specific key generation and signing are left to
 * the concrete subclasses.
 */
internal abstract class AbstractSoftwareCryptoProvider(
    protected val storage: TpmStorage,
    protected val x509PemReader: X509PemReader,
) : TpmProvider {

    protected var clientKey: KeyPair? = null

    override suspend fun isHardwareBacked(): Boolean = false

    override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray {
        check(p12File.isNotEmpty()) { "SM-B certificate .PEM file is empty" }
        return x509PemReader.loadCertificate(p12File, alias, password)
    }

    override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray {
        check(data.isNotEmpty()) { "SM-B certificate bytes are empty" }
        return x509PemReader.loadCertificateFromBytes(data, alias, password)
    }

    override suspend fun getRegistrationNumber(certificate: ByteArray): String {
        return x509PemReader.getRegistrationNumber(certificate).orEmpty()
    }

    override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray {
        val smbKey = x509PemReader.loadPrivateKey(p12File, alias, password)
        return signSmb(smbKey, input)
    }

    override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray {
        val smbKey = x509PemReader.loadPrivateKeyFromBytes(keystoreBytes, alias, password)
        return signSmb(smbKey, input)
    }

    override suspend fun randomUuid(): Uuid = Uuid.random()

    override suspend fun forget(resource: String?) {
        if (resource != null) {
            storage.deleteDpopKeys()
        } else {
            clientKey = null
            storage.deleteAllDpopKeys()
        }
    }

    /** Sign [signingInput] with the SM-B [privateKey] using the platform signer. */
    protected abstract suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray
}
