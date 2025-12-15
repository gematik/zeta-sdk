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

import de.gematik.zeta.sdk.crypto.EcdhSigner
import de.gematik.zeta.sdk.storage.InMemoryStorage
import joseToDerEcdsa
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class TpmProviderTest {

    private val tpmStorage: TpmStorage = TpmStorageImpl(InMemoryStorage())

    private val signer = EcdhSigner()

    private val tpmProvider = platformDefaultProvider(tpmStorage)

    @Before
    fun before() {
        Security.addProvider(BouncyCastleProvider())
    }

    @Test
    fun `given P12 file when read SM-B certificate and encode base64 then should equals expected`() = runTest {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = KEYSTORE_ALIAS
        val password = KEYSTORE_PASS

        // when
        val cert = tpmProvider.readSmbCertificate(p12file, alias, password)
        val base64cert = Base64.encode(cert)

        // then
        assertEquals(CERTIFICATE_DATA, base64cert)
    }

    @Test
    fun `given data and P12 file when read SM-B certificate sign and verify then should be valid signature`() = runTest {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = KEYSTORE_ALIAS
        val password = KEYSTORE_PASS

        val data = "some test data"

        // when
        val cert = tpmProvider.readSmbCertificate(p12file, alias, password)
        val signature = tpmProvider.signWithSmbKey(data.toByteArray(), p12file, alias, password)
        val valid = signer.verify(extractPublicKey(cert), data.toByteArray(), joseToDerEcdsa(signature))

        // then
        assertTrue(valid)
    }

    @Test
    fun `given data when generate DPoP key, sign and verify then should be valid signature`() = runTest {
        // given
        val data = "some test data"

        // when
        val key = tpmProvider.generateDpopKey()
        val signature = tpmProvider.signWithDpopKey(data.toByteArray())
        val valid = signer.verify(key.encoded, data.toByteArray(), joseToDerEcdsa(signature))

        // then
        assertTrue(valid)
    }

    @Test
    fun `given wrong pass when read SM-B certificate then should throw error`() = runTest {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = KEYSTORE_ALIAS
        val password = "wrong"

        // when + then
        val error = assertFailsWith<IOException> {
            tpmProvider.readSmbCertificate(p12file, alias, password)
        }
        assertEquals("keystore password was incorrect", error.message)
    }

    @Test
    fun `given wrong alias when sign with SM-B key then should throw error`() = runTest {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = "wrong"
        val password = KEYSTORE_PASS

        // when + then
        val error = assertFailsWith<IllegalArgumentException> {
            tpmProvider.signWithSmbKey("".toByteArray(), p12file, alias, password)
        }
        assertEquals("No key with alias 'wrong'", error.message)
    }

    private fun extractPublicKey(cert: ByteArray): ByteArray {
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = factory.generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
        return certificate.publicKey.encoded
    }

    companion object {
        private const val KEYSTORE_P12_FILE = "../test-keystore/test.p12"
        private const val KEYSTORE_ALIAS = "test"
        private const val KEYSTORE_PASS = "pass"
        private const val CERTIFICATE_DATA =
            "MIICBTCCAaygAwIBAgIUb/5wFQ2LA7wGXxGIFsqnN0f5JWEwCgYIKoZIzj0EAwIw" +
                "WDELMAkGA1UEBhMCVVMxDjAMBgNVBAgMBVN0YXRlMQ0wCwYDVQQHDARDaXR5MQww" +
                "CgYDVQQKDANPcmcxDTALBgNVBAsMBFVuaXQxDTALBgNVBAMMBFRlc3QwHhcNMjUx" +
                "MTEwMDgyMzQ1WhcNMzUxMTA4MDgyMzQ1WjBYMQswCQYDVQQGEwJVUzEOMAwGA1UE" +
                "CAwFU3RhdGUxDTALBgNVBAcMBENpdHkxDDAKBgNVBAoMA09yZzENMAsGA1UECwwE" +
                "VW5pdDENMAsGA1UEAwwEVGVzdDBaMBQGByqGSM49AgEGCSskAwMCCAEBBwNCAASG" +
                "g+NX1WZIQWg5rK7E+QeUvNSwL2lK6Vt3q8KnZFcPvTfRdD0dt1stRcY1SIbK0KIF" +
                "gHCCH+xfyfJ2GmAVayTCo1MwUTAdBgNVHQ4EFgQU9DMA0Y5AY0UsRsNfLIrDBaJv" +
                "VfIwHwYDVR0jBBgwFoAU9DMA0Y5AY0UsRsNfLIrDBaJvVfIwDwYDVR0TAQH/BAUw" +
                "AwEB/zAKBggqhkjOPQQDAgNHADBEAiANBYqfUQ/7Y5NccszWbcUtkOW20lPwoDIJ" +
                "3vh9Gmt1bQIgLr2lVBgfNxmIIh5cxYhOyztpShleKO3CmwERyOcxguw="
    }
}
