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

import kotlin.time.Clock

public data class TlsValidationResult(
    val isCompliant: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)

public object ZetaTlsValidator {
    public fun validateNegotiatedCipherSuite(negotiated: String): TlsValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (negotiated !in ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA) {
            errors += "Cipher suite '$negotiated' is not in the allowed list"
        }
        return TlsValidationResult(errors.isEmpty(), errors, warnings)
    }

    public fun validateNegotiatedProtocol(protocol: String): TlsValidationResult {
        val errors = mutableListOf<String>()
        if (protocol in ZetaTlsProtocols.FORBIDDEN) {
            errors += "Protocol '$protocol' is forbidden"
        }
        if (protocol !in ZetaTlsProtocols.ALLOWED) {
            errors += "Protocol '$protocol' is not in the allowed list"
        }
        return TlsValidationResult(errors.isEmpty(), errors, emptyList())
    }

    public fun validateEnabledCipherSuites(enabled: List<String>): TlsValidationResult {
        if (enabled.isEmpty()) return TlsValidationResult(true, emptyList(), emptyList())
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val normalizedEnabled = enabled.map { it.toOpenSslName() }.toSet()

        ZetaCipherSuites.REQUIRED_TLS_1_2.forEach { required ->
            if (required !in normalizedEnabled && required.toOpenSslName() !in normalizedEnabled) {
                errors += "Required cipher suite missing: $required"
            }
        }
        return TlsValidationResult(errors.isEmpty(), errors, warnings)
    }

    public fun validateAll(
        negotiatedCipher: String?,
        negotiatedProtocol: String?,
        enabledCiphers: List<String>,
        leafCert: ZetaCertInfo? = null,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): TlsValidationResult {
        val results = buildList {
            if (negotiatedCipher != null) add(validateNegotiatedCipherSuite(negotiatedCipher))
            if (negotiatedProtocol != null) add(validateNegotiatedProtocol(negotiatedProtocol))
            add(validateEnabledCipherSuites(enabledCiphers))
            if (leafCert != null) {
                val certResult = ZetaCertificateValidator.validate(leafCert, nowEpochSeconds)
                add(TlsValidationResult(certResult.isValid, certResult.errors, certResult.warnings))
            }
        }
        return TlsValidationResult(
            isCompliant = results.all { it.isCompliant },
            errors = results.flatMap { it.errors },
            warnings = results.flatMap { it.warnings },
        )
    }

    public fun validateHandshake(
        negotiatedCipher: String?,
        negotiatedProtocol: String?,
    ): TlsValidationResult {
        val results = buildList {
            if (negotiatedCipher != null) add(validateNegotiatedCipherSuite(negotiatedCipher))
            if (negotiatedProtocol != null) add(validateNegotiatedProtocol(negotiatedProtocol))
        }
        return TlsValidationResult(
            isCompliant = results.all { it.isCompliant },
            errors = results.flatMap { it.errors },
            warnings = results.flatMap { it.warnings },
        )
    }
}

public fun String.toOpenSslName(): String {
    if (this in ZetaCipherSuites.TLS_1_3_SUITES) return this
    return this
        .removePrefix("TLS_")
        .replace("_WITH_", "-")
        .replace("_", "-")
        .replace("AES-128-", "AES128-")
        .replace("AES-256-", "AES256-")
}
