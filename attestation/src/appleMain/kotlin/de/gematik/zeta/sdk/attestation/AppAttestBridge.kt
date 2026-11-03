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
package de.gematik.zeta.sdk.attestation

interface AppAttestBridge {
    suspend fun generateKey(): String
    suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray
    suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray
}

sealed class AppAttestException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AppAttestUnsupported(cause: Throwable? = null) :
    AppAttestException("App Attest not supported on this device", cause)

class AppAttestKeyGenerationFailed(cause: Throwable?) :
    AppAttestException("App Attest generateKey failed", cause)

class AppAttestServerUnavailable(cause: Throwable?) :
    AppAttestException("App Attest server unavailable, retry with the same key", cause)

class AppAttestInvalidKey(cause: Throwable?) :
    AppAttestException("App Attest key is invalid or no longer exists", cause)

class AppAttestAttestationFailed(cause: Throwable?) :
    AppAttestException("App Attest operation failed", cause)
