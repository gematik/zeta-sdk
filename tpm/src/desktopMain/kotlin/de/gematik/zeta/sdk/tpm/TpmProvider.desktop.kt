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

import de.gematik.zeta.sdk.crypto.X509PemReader
import derEcdsaToJose
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.EC.PrivateKey
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256

private class SoftwareCryptoProvider(
    storage: TpmStorage,
    x509PemReader: X509PemReader,
) : NativeSoftwareCryptoProvider(storage, x509PemReader) {

    override suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val priv = ec.privateKeyDecoder(EC.Curve.brainpoolP256r1)
            .decodeFromByteArray(PrivateKey.Format.DER, privateKey)
        val sig = priv.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignature(signingInput)
        return derEcdsaToJose(sig, 32)
    }
}

internal fun createDesktopSoftwareProvider(storage: TpmStorage): TpmProvider =
    SoftwareCryptoProvider(storage, X509PemReader())
