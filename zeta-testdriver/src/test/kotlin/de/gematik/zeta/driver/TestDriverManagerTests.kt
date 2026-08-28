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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.model.ConfigureRequest
import de.gematik.zeta.driver.model.SdkInstanceConfig
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("MaxLineLength")
class TestDriverManagerTest {
    private lateinit var manager: TestDriverManager
    private lateinit var initialConfig: SdkInstanceConfig

    @BeforeTest
    fun setup() {
        initialConfig = SdkInstanceConfig(
            fachdienstUrl = "https://initial.example.com",
            smbKeystoreFile = "",
            smbKeystoreB64 = """
                MIIOUwIBAzCCDf0GCSqGSIb3DQEHAaCCDe4Egg3qMIIN5jCCAX0GCSqGSIb3
                DQEHAaCCAW4EggFqMIIBZjCCAWIGCyqGSIb3DQEMCgECoIH+MIH7MGYGCSqG
                SIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRjWqxo/fhM0O3qltyWX6fbfPAh
                fQICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEAi91iKSxWvo
                UZWBN5LVijcEgZALa53JoppAVID8EDiRxJqfXxKXZBH4aKviEqm7IQZx0NSc
                G0IukRxmBUSP0sSG9bJBvTuGbWpv+Vdm5aKINCYRTivulGDNoPRVXZf2j2ye
                GIKYg+NnQCFk67l9MpXMwLVmq99esDE0hirSW1JJrtUyMAjIUCcTmWzeu7ZC
                M0PXafM18nd+9sVD3g+4NmIUPHIxUjAtBgkqhkiG9w0BCRQxIB4eAHoAZQB0
                AGEALgBjAF8AcwBtAGMAYgBfAGEAdQB0MCEGCSqGSIb3DQEJFTEUBBJUaW1l
                IDE3NjM1NjEyMjU3MjIwggxhBgkqhkiG9w0BBwagggxSMIIMTgIBADCCDEcG
                CSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQUd9c2
                nhLXbwpGX8YFhtPJTRJ7yk8CAggAAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZI
                AWUDBAEqBBDcwrINm3ilCbm2gXpQz1wEgIIL0Oe+jp8W6U+3ZRFZIQA2zswQ
                LhOG+xdexExwrIHtEPoUaUHxVA4ElokgIckq622jUOwHrYnyjj8iG6Eqi6KY
                cIy2xKcDFmMm/wHYJSqwWGdOl6vEI1jl+H1FdioXx3n6JvTKIL7Vdp73QLmS
                3sCvyodLCC5TDvuKkh7olTRKD/yU9DtutJukJlsa06MjYTv7FXLrqu9gJxFo
                umfpUcizRnLR5sVvtpSNun6dgZWuCIk/8eNclPb/AmxB9O731eW+8tc5DepG
                vliOHXhQducKjiZFShnMfy+KmMoKDH88hw65FQ0dFQpuh28ZV9BQoAz0TF5q
                R5oXuu7K5SGRwGaBghDveBWO4HLuGHK/4jwKJcqh9kMVJ3vUm4OvHKTIVNXd
                Nu+00Fwtnmjc9q0EG5K4wnJvUt46JmYlm2K2MGRJf8FyUrATLw0Kmr0v+WBb
                2ycyZSThL3igHVcRxdlzYuyQJ3SzmJi84rKsFuXXg/nuBr2sNhdHcBSJN3tb
                +RjzasyZpFUC4M5Kt+WRAjnRNesFfBbF/ir2oIGgJt4/uXuE1b68EwNB7s28
                Mk8AbFpit/Z4MLGD9PZSWzqMKm5e0Y/rmhowEaf/ncZ879WQNdjC3bpi5jGZ
                Io8++DIPTMyqG4KYyjGPKJTUkZM6l/8kFHjH3pdfvkGuxzIgyHOpYioywumcG
                e+AlRSCDYDRSt29s0EWnMYnmeJqw6r+hY5JWAYcErAHiTvG73fwRfij3D+Aj4
                qPM/rF6rGRwi2ZGrDm2QCYvByJVSZ1BOfTa32aby9jAGn6Fn1FoKJv0VAgfer
                ci7e9sXmTqArvd+erwMATi+/dIMt3oVcMzXP9PIKg/Z7M0voA3HrCtratHRnU
                gBgpOpxP9ZT+/O8ri9YJGhIvO+R/Xj9oIhMUIrzqIs7SkfGMqKnk9w35cb3FW
                wWAW3VH6qGUfKPGAxKPY7McDqp8lXqtn0RQJGFZSwCGlqTYhtzKRxPgOGgN36
                Q2P8AGfvJ21EedjZrPtQI4h+shso6arJGQdIKwtyXFjjobv9DC3hhZbsbM5Vz
                5q2IDUBXyGjpPofSnnM+5RpYhywrLbfr9ai/CGQPaG6W7ebx6crC7/uPqQgBG
                uMms9A4iS1ht7Q50+H5kkGUkuVJDXtG8adgalNIxVALyCDK853dwzqroVmid/k
                7jp2+HMOeDkOBjcbd/IgaCqHBV5ATIYaX1EAhKjCWL0LiUC0gSwacqP/YFQLgH
                9Cs02A452gjtTJ0ZxZw8cs2F8e0xp7vB6vj3wLyE4EtHAECcTPK/1wJJC+WDYU
                SXBAHE0Iwl3pZRIvWTgYMnMt6BkAkyoUVkM8bnGv9JMqxyYI3h8svaf9ye8ipk
                MpulY7iBvwAEFXY+b2+soO/QtUZy/E60v2zgrrpEkUPVwOFUwDOjpY9cBUqZbr
                +awMSbUovzLgXloJ1uyq3mfrq5YqOOORdcMycr8pCU6QkW+HaDVV+G3dBTIf5S
                rEyEN4b6eCEZGvqmOGNSQv/xKTxsVnghkn9+vuTjTiViQiuXQVpZN7VbHEg+tK
                aystaYT+RGFtfZczmu9ysdc2jq5Ow9ohwVhabeVXW1FiBf6MbU4mICdoLL6yT1
                mMWdfJ+M/QoXa68t2x1t9Gd+avHe58Kq30gWTFsOnaBA8brKK8ytYlpu/yM4jb
                4N1DE22xwN6WLRxcwyEkzjZzvg4KX20kE98RkFqBxuw3i/CFV+S7DgUYuDGqRd
                Td9xuz5LmKFprCEPdXw2SP3x9bt47ax0/nFnplGVNcu4JKE03EF2ERuQCrEKO6
                13nXZAZaPT7596YCjQrpQvdeNJ5cBdoSjZ8mBTFF9hIgefhSwhMmA/lfdkPF4h
                WhvBFhwFiNb1P8mAO7W9g/VOj52fDZEcwYxjBMyc0BkQxFFSt57WD3wy23Ybrv
                TIC0PmrrTqUUkoFjEij+IyGboe4lJ9OrB8RMGGyTu/hR1qhVyNz24Xmpb+jjPi
                xwv+4Tkiil5rO6xOcO4llLaP/JppRWbe4/RkbCStJGzA3lVCZ0W2HaJ5A71dx9
                q4iD1d9RA/zceN19olGO3jxRXcTwvqMBDaYpdLlkpTv/Zt/+BdSG2LkGTyxEZs
                flDQX9uHQMKyMajGaP//FNkUrmvgUebI6OMzuwyJtMG8fqLngXmnEaYk0FUllJ
                Ph8pytYrpZPRfljjh1vJUo1k8IjH+0nYO3gcPD51jynVcQOUKtytj3dNPaxy8V
                CTCqvfIzAdyvkJWhCYvn6De45F814rlNlNXhSbYj3RqmGm6jnit2KTlmINVCSS
                isV7dHTw7Cw22NGDxXVbBMdmbNq3XI1anG0SsRYsC+DX41IlGy5h9wSL7AOTcg
                LymqeTl/Mojei57GJi/yjLbFmn/as4hCQ/M1Jx+6r3P8Z22Oy493/15qkqlV6/
                ZZq5r70coV0/BXacFc+Xq8HMk6uZGTIrdlFhV5qnVyiVDRNCxlZQcyWLSIZWqX
                Evqe8M69FnDTbn1mzLMaRTYITZ+7H4+F+HJu5NWYLioTsz0GeK3Bxb2yJ5kjvJ
                t+SkaVXA3lz/tqUrraxO/qPNeAqeFfMQ6y1a67ADC0roaHnbThFFsVjyCwRzFZ
                I8lkR5okp1InR9vddNLFsREf3bZ15jE7CHdgEX2ejApstf209TvFREC1sRxYO
                xFzBVZteDDu2YKTN1fd3HHXrBB1j9cMMVPmLcU8dX/qNGadiv/sSHCR/orasHD
                346tP5D1DQq/ChAr1VDQXLHGTXhe4AGGk/gAosy5EgZpe0PkcLweKA5jd+D8U
                18Suw7QpOUabZxpmqs40CzvzrEotzRSufv4IXKyzM0o/FGPUXX+7Boa3/0zWu
                HzUPgxTuIkdJ7C+Tk9iqQm1QX+HoXuXyg2x3MwZPBZ3+sQw5W2b2AHm/khvPC
                hD4uX0gkawjQK6OwLmTqBpD5KupuRJTyjsIpC1mKYFoYis4gNGjKzAHASkE7R0
                oxVjC7V6ULJnXg4nEN7NKmtAU1PDgU+RFZ9EGdLzPdaO+az4oDtCkcK+XCGhzG
                i7pst13Qwaz/LaZwYy2O2+dG3NxFjItVZTD44D4cTV1C9FaUAOVLpQg/sCvih+
                kxw27wc+S5vSWo6+rX0aLhHfmBRAfUUcGZnhC9JtoGkwIKpdJJajUjTleaLF2D
                0w3i3KLp4iD8+nc6hNCaaLphIDIRnHJAuD1jiREW3KzyiHxvoVpJYrq9lcXe0C
                41QejgXvUI/cNGnBUEmd6jIh4hZfBHIuu1rjDeBNorUnXfYEWp8zznZMgXKkOg
                fHhObiTqXCBuxFDmAWjTuSftod5uWOhUMJ6dMfKvU0/OfoqlF+h00eCnag9X3S
                t0B5Uy3zBsht69aUhIxS2U2gkvJb8A7xsGeEzDlld/JWxfIx2GtdMN0je5zbja
                H+Y3iAWj/hKWnMlm9L5xgjzze1MUzn9YT+38mJU0tK1ndJOd8Af0EFT5teOobK
                BamKs/LprbnB1a9PTJ1TUHdfjw5tPwFEZvgCFOgsSdJAK7ijo0OOmmCY1H7f/o
                56x5D5Hn0byO+IT8b2cj60fxi70Cd/SVGVhjEf5AdF8nKDcCbn90rINRcMx4Y1
                OoYS+oyhitHts0S4bIzQGq0tnP8gJDnr7i0xW64TUEB6ppMaD/7SgUsdu/HIbrk
                WnLZwJ6KWXMUVAiFRmUf028haakkTvn96MPq0OhMgGL4y+BYwwyHNVf5Ja5odz
                BM65SnfrmcZ1NNe3/E6dI3Qq0bokRtbcMfY3rF0oHgzZBsIwCbV/CKPOLEDOqy
                209atarv5JT2tEEQOqDiHtPEc5R1Rqx60sHWTDiasDP49BNYnU9CH2eb+FzonA
                nvhIwTMLrh2rsqhWMxUhC0JSvhlt+W1rwqgmrjQ0olCnAjikVeRVMeVp5pOihI
                L9UbgUZZ8xMI/HE8HgIDTzO+QU75mvvsT6D5H9Fw9Fvsg1Q2M+DtNLm5Ztrgu4G
                Qrh60rGIci/vzmmxQL4xlrxyLAaYk97RmpqUxdoIMeUet3MC68TXCF0Rjs21MWC
                BSEENjBNMDEwDQYJYIZIAWUDBAIBBQAEIPlJWHjU++nrFabCr9JoDegYigFUMnC
                Ma4SGBwoYbz5kBBTXQrwHIN+OLGM8DOyKldlSScEGaAICCAA=
                """,
            smbKeystoreAlias = "123",
            smbKeystorePassword = "123",
            aslProdEnv = true,
            poppToken = "",
            disableTlsVerification = true,
            requiredOid = "",
        )
        manager = TestDriverManager(initialConfig)
    }

    @Test
    fun constructor_initializesWithProvidedConfig() {
        // Assert
        assertEquals("https://initial.example.com", manager.config.fachdienstUrl)
        assertEquals(true, manager.config.disableTlsVerification)
        assertNotNull(manager.sdk)
        assertNotNull(manager.httpClient)
    }

    @Test
    fun notifications_returnsClient_whenNotificationsAreEnabled() {
        // Act
        val client = manager.notifications()

        // Assert: NotificationConfig() is wired into newSdk, so the lazy client is available
        // (discovery/network happens only on first NS call, not here).
        assertNotNull(client)
    }

    @Test
    fun notifications_followsRebuiltSdk_afterConfigure() {
        // Arrange
        val before = manager.notifications()

        // Act
        manager.configure(
            ConfigureRequest(
                resource = "https://new.example.com",
                disableTlsVerification = true,
                caCertificatePem = "",
            ),
        )
        val after = manager.notifications()

        // Assert: rebuildClient() produces a fresh SDK, hence a fresh notification client
        assertNotNull(after)
        assertNotSame(before, after)
    }

    @Test
    fun configure_updatesDisableTlsVerification_whenProvided() {
        // Arrange
        val request = ConfigureRequest(
            resource = "",
            disableTlsVerification = true,
            caCertificatePem = "",
        )

        // Act
        manager.configure(request)

        // Assert
        assertEquals(true, manager.config.disableTlsVerification)
    }

    @Test
    fun configure_updatesFachdienstUrl_whenResourceProvided() {
        // Arrange
        val request = ConfigureRequest(
            resource = "https://new-resource.example.com",
            disableTlsVerification = true,
            caCertificatePem = "",
        )

        // Act
        manager.configure(request)

        // Assert
        assertEquals("https://new-resource.example.com", manager.config.fachdienstUrl)
    }

    @Test
    fun configure_keepsOriginalUrl_whenResourceIsEmpty() {
        // Arrange
        val request = ConfigureRequest(
            resource = "",
            disableTlsVerification = true,
            caCertificatePem = "",
        )

        // Act
        manager.configure(request)

        // Assert
        assertEquals("https://initial.example.com", manager.config.fachdienstUrl)
    }

    @Test
    fun configure_rebuildsClient_whenCalled() {
        // Arrange
        val oldSdk = manager.sdk
        val oldHttpClient = manager.httpClient

        val request = ConfigureRequest(
            resource = "https://new.example.com",
            disableTlsVerification = true,
            caCertificatePem = "",
        )

        // Act
        manager.configure(request)

        // Assert
        assertNotSame(oldSdk, manager.sdk)
        assertNotSame(oldHttpClient, manager.httpClient)
    }

    @Test
    fun setKvnr_updatesConfigAndRebuildsClient() {
        // Arrange
        val oldSdk = manager.sdk

        // Act
        manager.setKvnrEmail("X110411675", "test@mail.com")

        // Assert
        assertEquals("X110411675", manager.config.oidcTestKvnr)
        assertEquals("test@mail.com", manager.config.oidcBindingEmail)
        assertNotSame(oldSdk, manager.sdk)
    }

    @Test
    fun reset_restoresDefaultConfig_whenNoConfigProvided() {
        // Arrange
        val request = ConfigureRequest(
            resource = "https://custom.example.com",
            disableTlsVerification = true,
            caCertificatePem = "some-cert",
        )
        manager.configure(request)

        // Act
        manager.reset(initialConfig)

        // Assert
        assertEquals("https://initial.example.com", manager.config.fachdienstUrl)
        assertEquals(true, manager.config.disableTlsVerification)
    }

    @Test
    fun reset_rebuildsClient() {
        // Arrange
        val oldSdk = manager.sdk
        val oldHttpClient = manager.httpClient

        // Act
        manager.reset(initialConfig)

        // Assert
        assertNotSame(oldSdk, manager.sdk)
        assertNotSame(oldHttpClient, manager.httpClient)
    }

    @Test
    fun getStorageSnapshot_returnsJsonObject_whenStorageHasData() {
        // Arrange
        val storage = manager.getStorage()
        storage.map["key1"] = "value1"
        storage.map["key2"] = """{"nested": "json"}"""

        // Act
        val snapshot = manager.getStorageSnapshot()

        // Assert
        assertTrue(snapshot.size >= 2)
        assertNotNull(snapshot["key1"])
        assertNotNull(snapshot["key2"])
    }

    @Test
    fun getStorageSnapshot_parsesJsonValues_whenPossible() {
        // Arrange
        val storage = manager.getStorage()
        storage.map["jsonKey"] = """{"name": "test", "value": 123}"""
        storage.map["stringKey"] = "plain text"

        // Act
        val snapshot = manager.getStorageSnapshot()

        // Assert
        assertTrue(snapshot.size >= 2)
        val jsonValue = snapshot["jsonKey"]
        assertNotNull(jsonValue)
        assertTrue(jsonValue is kotlinx.serialization.json.JsonObject)
        val stringValue = snapshot["stringKey"]
        assertNotNull(stringValue)
        assertTrue(stringValue is kotlinx.serialization.json.JsonPrimitive)
    }

    @Test
    fun getStorageSnapshot_returnsOnlySemanticKeys_whenStorageIsEmpty() {
        // Act
        val snapshot = manager.getStorageSnapshot()

        // Assert
        assertTrue(snapshot.size <= 1)
    }

    @Test
    fun getStorage_returnsSameInstance() {
        // Act
        val storage1 = manager.getStorage()
        val storage2 = manager.getStorage()

        // Assert
        assertSame(storage1, storage2)
    }

    @Test
    fun configure_multipleChanges_appliesAllCorrectly() {
        // Arrange
        val request = ConfigureRequest(
            resource = "https://multi-change.example.com",
            disableTlsVerification = true,
            caCertificatePem = "-----BEGIN CERTIFICATE-----\nMULTI\n-----END CERTIFICATE-----",
        )

        // Act
        manager.configure(request)

        // Assert
        assertEquals("https://multi-change.example.com", manager.config.fachdienstUrl)
        assertEquals(true, manager.config.disableTlsVerification)
        assertNotNull(manager.sdk)
        assertNotNull(manager.httpClient)
    }
}
