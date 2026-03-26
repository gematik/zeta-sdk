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

package de.gematik.zeta.sdk.network.http.client.config.tls

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCertificateValidator.ZetaCertificateAlgorithms.ALLOWED_CURVES_NORMALIZED
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaSignatureAlgorithms.MIN_EC_KEY_BITS
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaSignatureAlgorithms.MIN_RSA_KEY_BITS

public data class CertificateValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)

public data class ZetaCertInfo(
    val subjectDN: String,
    val sigAlgName: String,
    val keyAlgorithm: String,
    val keySize: Int,
    val curveName: String? = null,
    val notBefore: Long,
    val notAfter: Long,
)

public object ZetaCertificateValidator {
    public object ZetaCertificateAlgorithms {
        public val ALLOWED_SIGNATURE_ALGORITHMS: Set<String> = setOf(
            "SHA256WITHECDSA",
            "SHA256WITHRSA",
            "RSASSA-PSS",
            "SHA256WITHRSAANDMGF1",
        )
        public val FORBIDDEN_SIGNATURE_ALGORITHMS: Set<String> = setOf(
            "SHA1WITHECDSA",
            "SHA1WITHRSA",
            "MD5WITHRSA",
            "MD2WITHRSA",
        )
        internal val ALLOWED_CURVES_NORMALIZED: Set<String> =
            ZetaTlsCurves.ALLOWED.map { it.normalize() }.toSet()

        public const val MIN_RSA_KEY_BITS: Int = 2048
        public const val EC_P256_KEY_SIZE_BITS: Int = 256
        public const val EC_P384_KEY_SIZE_BITS: Int = 384
        public const val MIN_EC_KEY_BITS: Int = EC_P256_KEY_SIZE_BITS
    }

    public fun validate(cert: ZetaCertInfo, nowEpochSeconds: Long): CertificateValidationResult {
        val errors = mutableListOf<String>()
        val sigAlg = cert.sigAlgName.normalize()

        if (sigAlg in ZetaCertificateAlgorithms.FORBIDDEN_SIGNATURE_ALGORITHMS) {
            errors += "Forbidden signature algorithm '${cert.sigAlgName}' in cert: ${cert.subjectDN}"
        } else if (sigAlg !in ZetaCertificateAlgorithms.ALLOWED_SIGNATURE_ALGORITHMS) {
            errors += "Signature algorithm '${cert.sigAlgName}' is not allowed"
        }

        when (cert.keyAlgorithm.uppercase()) {
            "RSA" -> if (cert.keySize < MIN_RSA_KEY_BITS) {
                errors += "RSA key too small: ${cert.keySize} (min: $MIN_RSA_KEY_BITS)"
            }

            "EC" -> {
                if (cert.keySize < MIN_EC_KEY_BITS) {
                    errors += "EC key too small: ${cert.keySize} (min: $MIN_EC_KEY_BITS)"
                }
                cert.curveName?.let { curve ->
                    if (curve.normalize() !in ALLOWED_CURVES_NORMALIZED) {
                        errors += "EC curve '$curve' is not allowed"
                    }
                }
            }
        }

        if (nowEpochSeconds < cert.notBefore) {
            errors += "Certificate not yet valid: ${cert.subjectDN}"
        }
        if (nowEpochSeconds > cert.notAfter) {
            errors += "Certificate has expired: ${cert.subjectDN}"
        }

        return CertificateValidationResult(errors.isEmpty(), errors, emptyList())
    }

    public fun validateChain(
        chain: List<ZetaCertInfo>,
        nowEpochSeconds: Long,
    ): CertificateValidationResult {
        val results = chain.map { validate(it, nowEpochSeconds) }
        return CertificateValidationResult(
            isValid = results.all { it.isValid },
            errors = results.flatMap { it.errors },
            warnings = results.flatMap { it.warnings },
        )
    }

    private fun String.normalize() = uppercase().replace("-", "").replace("_", "")
}
