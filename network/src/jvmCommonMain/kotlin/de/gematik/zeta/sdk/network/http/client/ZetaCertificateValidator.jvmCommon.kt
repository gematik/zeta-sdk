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

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCertInfo
import de.gematik.zeta.sdk.network.http.client.config.tls.certificateMatchesHost
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.GeneralName
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

public fun X509Certificate.toZetaCertInfo(): ZetaCertInfo {
    val keyAlgorithm = publicKey.algorithm
    val keySize: Int
    val curveName: String?

    when (val pub = publicKey) {
        is ECPublicKey -> {
            keySize = pub.params.order.bitLength()
            curveName = when (keySize) {
                256 -> "secp256r1"
                384 -> "secp384r1"
                521 -> "secp521r1"
                else -> null
            }
        }

        is RSAPublicKey -> {
            keySize = pub.modulus.bitLength()
            curveName = null
        }

        else -> {
            keySize = 0
            curveName = null
        }
    }

    val sanDnsNames = subjectAlternativeNames
        ?.filter { (it[0] as? Int) == 2 }
        ?.mapNotNull { it[1] as? String }
        ?: emptyList()

    return ZetaCertInfo(
        subjectDN = subjectX500Principal.name,
        sigAlgName = sigAlgName,
        keyAlgorithm = keyAlgorithm,
        keySize = keySize,
        curveName = curveName,
        notBefore = notBefore.time / 1000,
        notAfter = notAfter.time / 1000,
        san = sanDnsNames,
        commonName = mostSpecificCommonName(),
    )
}

internal fun X509Certificate.mostSpecificCommonName(): String? =
    X500Name.getInstance(subjectX500Principal.encoded)
        .getRDNs(BCStyle.CN)
        .lastOrNull()
        ?.first
        ?.value
        ?.let { (it as? ASN1String)?.string }

internal fun X509Certificate.matchesHost(host: String): Boolean {
    val sans = subjectAlternativeNames.orEmpty()
    fun sansOfType(type: Int) = sans.filter { it[0] == type }.map { it[1] as String }

    return if (host.isIpAddress()) {
        sansOfType(GeneralName.iPAddress).any { it.equals(host, ignoreCase = true) }
    } else {
        certificateMatchesHost(sansOfType(GeneralName.dNSName), mostSpecificCommonName(), host)
    }
}

private val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private fun String.isIpAddress(): Boolean = ':' in this || IPV4_REGEX.matches(this)
