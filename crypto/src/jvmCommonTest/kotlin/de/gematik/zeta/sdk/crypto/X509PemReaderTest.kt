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

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("FunctionNaming")
class X509PemReaderTest {

    private val x509Reader = X509PemReader()

    @Test
    fun `given P12 file when load certificate then should match base64 data`() {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = KEYSTORE_ALIAS
        val password = KEYSTORE_PASS

        // when
        val cert = x509Reader.loadCertificate(p12file, alias, password)
        val base64cert = Base64.Default.encode(cert)

        // then
        assertEquals(BASE64_CERTIFICATE_DATA, base64cert)
    }

    @Test
    fun `given P12 file when load private key then should match base64 data`() {
        // given
        val p12file = KEYSTORE_P12_FILE
        val alias = KEYSTORE_ALIAS
        val password = KEYSTORE_PASS

        // when
        val cert = x509Reader.loadPrivateKey(p12file, alias, password)
        val base64cert = Base64.Default.encode(cert)

        // then
        assertEquals(BASE64_PRIVATE_KEY, base64cert)
    }

    companion object {
        private const val KEYSTORE_P12_FILE = "../test-keystore/test.p12"
        private const val KEYSTORE_ALIAS = "test"
        private const val KEYSTORE_PASS = "pass"
        private const val BASE64_CERTIFICATE_DATA =
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
        private const val BASE64_PRIVATE_KEY =
            "MIGIAgEAMBQGByqGSM49AgEGCSskAwMCCAEBBwRtMGsCAQEEIADEGUSXBKNrl3Yp" +
                "gPAiglx9eullDFNMI2XYSnCIUu/noUQDQgAEhoPjV9VmSEFoOayuxPkHlLzUsC9p" +
                "Sulbd6vCp2RXD7030XQ9HbdbLUXGNUiGytCiBYBwgh/sX8nydhpgFWskwg=="
    }
}
