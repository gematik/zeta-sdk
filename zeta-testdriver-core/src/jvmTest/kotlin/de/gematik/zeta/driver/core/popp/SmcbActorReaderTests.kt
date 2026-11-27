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

package de.gematik.zeta.driver.core.popp

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.asn1.isismtt.x509.Admissions
import org.bouncycastle.asn1.isismtt.x509.ProfessionInfo
import org.bouncycastle.asn1.x500.DirectoryString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmcbActorReaderTests {

    @Test
    fun fromKeystoreBytes_returnsTelematikIdAndProfessionOid() {
        val p12 = keystore("1-20000300139", listOf("1.2.276.0.76.4.50"))

        assertEquals(
            PoppActor("1-20000300139", "1.2.276.0.76.4.50"),
            SmcbActorReader.fromKeystoreBytes(p12, ALIAS, PASSWORD),
        )
    }

    @Test
    fun fromKeystoreBytes_returnsFirstProfessionOid() {
        val p12 = keystore("1-20000300139", listOf("1.2.276.0.76.4.50", "1.2.276.0.76.4.30"))

        assertEquals("1.2.276.0.76.4.50", SmcbActorReader.fromKeystoreBytes(p12, ALIAS, PASSWORD).actorProfessionOid)
    }

    @Test
    fun fromKeystoreBytes_throws_whenAdmissionMissing() {
        val p12 = keystore(null, emptyList())

        assertFailsWith<IllegalStateException> { SmcbActorReader.fromKeystoreBytes(p12, ALIAS, PASSWORD) }
    }

    @Test
    fun fromKeystoreFile_readsSameActorAsBytes() {
        val p12 = keystore("1-20000300139", listOf("1.2.276.0.76.4.50"))
        val file = File.createTempFile("smcb", ".p12").apply { deleteOnExit(); writeBytes(p12) }

        assertEquals(
            SmcbActorReader.fromKeystoreBytes(p12, ALIAS, PASSWORD),
            SmcbActorReader.fromKeystoreFile(file.absolutePath, ALIAS, PASSWORD),
        )
    }

    private fun keystore(registrationNumber: String?, oids: List<String>): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val subject = X500Name("CN=SMC-B Test")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.ONE,
            Date(System.currentTimeMillis() - 60_000),
            Date(System.currentTimeMillis() + 3_600_000),
            subject,
            keyPair.public,
        )
        if (registrationNumber != null) {
            val info = ProfessionInfo(
                null,
                arrayOf(DirectoryString("Betriebsstätte Arzt")),
                oids.map { ASN1ObjectIdentifier(it) }.toTypedArray(),
                registrationNumber,
                null,
            )
            val syntax = AdmissionSyntax(null, DERSequence(Admissions(null, null, arrayOf(info))))
            builder.addExtension(ISISMTTObjectIdentifiers.id_isismtt_at_admission, false, syntax)
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(builder.build(signer))
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf(cert))
        }
        return ByteArrayOutputStream().also { store.store(it, PASSWORD.toCharArray()) }.toByteArray()
    }

    private companion object {
        const val ALIAS = "smcb"
        const val PASSWORD = "test"
    }
}
