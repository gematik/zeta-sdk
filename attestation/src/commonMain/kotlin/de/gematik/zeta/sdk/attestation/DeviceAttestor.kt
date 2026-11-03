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

enum class HardwareAttestationType { APPLE, ANDROID }

enum class RegisteredAttestationType(val wireName: String) {
    APPLE("apple"),
    ANDROID("android"),
    SOFTWARE("software"),
    ;

    companion object {
        fun fromWireName(name: String?): RegisteredAttestationType? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Platform-neutral entry point for hardware attestation.
 */
interface DeviceAttestor {
    val attestationType: HardwareAttestationType

    /** Whether hardware attestation is available on this device (false on simulators / old hardware). */
    fun isSupported(): Boolean

    /**
     * Initial attestation bound to `hash_puk_client_sig = SHA-256(PuK.Client.Sig)` for DCR. [pukClientSig] is the
     * 65-byte uncompressed P-256 point of PuK.Client.Sig. Throws on failure.
     */
    suspend fun attest(pukClientSig: ByteArray): DeviceAttestationEvidence

    /** Runtime evidence over [clientDataHash] for the client statement (Apple: dc_assertion CBOR). */
    suspend fun runtimeEvidence(keyId: String, clientDataHash: ByteArray): ByteArray

    /** Fast-path `attestation_pop` over [hashPukClientSig] = SHA-256(PuK.Client.Sig) (Apple: CBOR assertion). */
    suspend fun proveAttestationKey(hashPukClientSig: ByteArray): ByteArray

    /** True when an attested hardware key id is stored, so [runtimeEvidence] / [proveAttestationKey] can use it. */
    suspend fun hasAttestedKey(): Boolean

    /** Deletes the platform key material of this registration (no-op where the platform cannot delete it). */
    suspend fun forget()
}

sealed interface DeviceAttestationEvidence {
    /** Apple App Attest: `keyId` from `generateKey`, raw CBOR attestation object from `attestKey`. */
    class Apple(val keyId: String, val attestationObject: ByteArray) : DeviceAttestationEvidence {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Apple && keyId == other.keyId && attestationObject.contentEquals(other.attestationObject))

        override fun hashCode(): Int = 31 * keyId.hashCode() + attestationObject.contentHashCode()
    }

    /** Android Key Attestation: DER certificate chain of `Alias_AK`, leaf first, up to the Google root; optional Play Integrity classic token. */
    class Android(
        val keyAttestationCertificateChain: List<ByteArray>,
        val playIntegrityToken: String? = null,
    ) : DeviceAttestationEvidence {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Android &&
                    playIntegrityToken == other.playIntegrityToken &&
                    keyAttestationCertificateChain.size == other.keyAttestationCertificateChain.size &&
                    keyAttestationCertificateChain.zip(other.keyAttestationCertificateChain).all { (a, b) -> a.contentEquals(b) }
                )

        override fun hashCode(): Int =
            31 * keyAttestationCertificateChain.fold(1) { acc, cert -> 31 * acc + cert.contentHashCode() } + playIntegrityToken.hashCode()
    }
}
