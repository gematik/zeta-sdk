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

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(InternalZetaApi::class)
class ZetaSdkNotificationsJvmTest {
    @Test
    fun notificationsForTesting_returnsMemoizedClient_forBuiltSdk() = runTest {
        // Arrange
        val client = ZetaSdk.build("https://fachdienst.example.com", buildConfig())

        // Act / Assert
        assertSame(client.notificationsForTesting(), client.notificationsForTesting())
        client.close()
    }

    @Test
    fun notificationsForTesting_throws_forForeignImplementations() {
        // Arrange
        val foreign = io.mockk.mockk<ZetaSdkClient>()

        // Act / Assert
        assertFailsWith<UnsupportedOperationException> { foreign.notificationsForTesting() }
    }

    @Test
    fun notificationsForTesting_throws_whenNotificationsNotConfigured() = runTest {
        // Arrange - notificationConfig omitted (null) disables notifications entirely
        val client = ZetaSdk.build("https://fachdienst.example.com", buildConfig(notificationConfig = null))

        // Act / Assert
        assertFailsWith<IllegalStateException> { client.notificationsForTesting() }
        client.close()
    }

    private fun buildConfig(notificationConfig: NotificationConfig? = NotificationConfig()) = BuildConfig(
        productId = "product-123",
        productVersion = "1.0.0",
        clientName = "TestClient",
        storageConfig = StorageConfig.Custom(InMemoryStorage()),
        tpmConfig = object : TpmConfig {},
        authConfig = AuthConfig(
            listOf("scope-a"),
            300,
            false,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = "1.2.276.0.76.4.261",
        ),
        platformProductId = PlatformProductId.LinuxProductId("linux", "", "test", "1.0"),
        notificationConfig = notificationConfig,
    )
}
