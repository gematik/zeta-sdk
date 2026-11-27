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

import de.gematik.zeta.driver.model.SdkInstanceConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadInstanceManagerTest {

    private lateinit var configProvider: FakeConfigProvider
    private lateinit var manager: LoadInstanceManager

    @BeforeTest
    fun setup() {
        configProvider = FakeConfigProvider()
        manager = LoadInstanceManager(configProvider, PoppTokenSource { null })
    }

    @AfterTest
    fun teardown() {
        manager.clear()
    }

    @Test
    fun createInstances_createsRequestedCount_whenValidCountProvided() = runTest {
        // Arrange
        val count = 3

        // Act
        val created = manager.createInstances(count)

        // Assert
        assertEquals(3, created.size)
        assertEquals(3, manager.listInstances().size)
    }

    @Test
    fun createInstances_createsZeroInstances_whenCountIsZero() = runTest {
        // Act
        val created = manager.createInstances(0)

        // Assert
        assertEquals(0, created.size)
        assertEquals(0, manager.listInstances().size)
    }

    @Test
    fun createInstances_createsZeroInstances_whenCountIsNegative() = runTest {
        // Act
        val created = manager.createInstances(-5)

        // Assert
        assertEquals(0, created.size)
    }

    @Test
    fun createInstances_usesProvidedConfigs_whenConfigsProvided() = runTest {
        // Arrange
        val config = createTestConfig(id = 100, url = "https://custom.com")
        val providedConfigs = listOf(config)

        // Act
        val created = manager.createInstances(1, providedConfigs)

        // Assert
        assertEquals(1, created.size)
        assertEquals(100, created[0])
        val instance = manager.getInstance(100)
        assertNotNull(instance)
        assertEquals("https://custom.com", instance.config.fachdienstUrl)
    }

    @Test
    fun createInstances_skipsExistingInstances_whenIdAlreadyExists() = runTest {
        // Arrange
        val config = createTestConfig(id = 50)
        manager.createInstances(1, listOf(config))

        // Act
        val created = manager.createInstances(1, listOf(config))

        // Assert
        assertEquals(0, created.size)
        assertEquals(1, manager.listInstances().size)
    }

    @Test
    fun createInstances_incrementsIdGenerator_whenNoIdProvided() = runTest {
        // Act
        val created1 = manager.createInstances(1)
        val created2 = manager.createInstances(1)

        // Assert
        assertEquals(1, created1[0])
        assertEquals(2, created2[0])
    }

    @Test
    fun getInstance_returnsInstance_whenInstanceExists() = runTest {
        // Arrange
        val created = manager.createInstances(1)
        val id = created[0]

        // Act
        val instance = manager.getInstance(id)

        // Assert
        assertNotNull(instance)
        assertEquals(id, instance.id)
    }

    @Test
    fun getInstance_returnsNull_whenInstanceDoesNotExist() {
        // Act
        val instance = manager.getInstance(999)

        // Assert
        assertNull(instance)
    }

    @Test
    fun listInstances_returnsEmptyList_whenNoInstancesExist() {
        // Act
        val instances = manager.listInstances()

        // Assert
        assertEquals(0, instances.size)
    }

    @Test
    fun listInstances_returnsSortedList_whenMultipleInstancesExist() = runTest {
        // Arrange
        val config1 = createTestConfig(id = 3)
        val config2 = createTestConfig(id = 1)
        val config3 = createTestConfig(id = 2)
        manager.createInstances(1, listOf(config1))
        manager.createInstances(1, listOf(config2))
        manager.createInstances(1, listOf(config3))

        // Act
        val instances = manager.listInstances()

        // Assert
        assertEquals(3, instances.size)
        assertEquals(1, instances[0].id)
        assertEquals(2, instances[1].id)
        assertEquals(3, instances[2].id)
    }

    @Test
    fun deleteInstances_removesSpecificInstances_whenIdsProvided() = runTest {
        // Arrange
        val created = manager.createInstances(3)
        val toDelete = listOf(created[0], created[2])

        // Act
        val removed = manager.deleteInstances(toDelete)

        // Assert
        assertEquals(2, removed.size)
        assertTrue(removed.contains(created[0]))
        assertTrue(removed.contains(created[2]))
        assertEquals(1, manager.listInstances().size)
        assertNotNull(manager.getInstance(created[1]))
    }

    @Test
    fun deleteInstances_removesAllInstances_whenIdsIsNull() = runTest {
        // Arrange
        manager.createInstances(3)

        // Act
        val removed = manager.deleteInstances(null)

        // Assert
        assertEquals(3, removed.size)
        assertEquals(0, manager.listInstances().size)
    }

    @Test
    fun deleteInstances_removesAllInstances_whenIdsIsEmpty() = runTest {
        // Arrange
        manager.createInstances(3)

        // Act
        val removed = manager.deleteInstances(emptyList())

        // Assert
        assertEquals(3, removed.size)
        assertEquals(0, manager.listInstances().size)
    }

    @Test
    fun deleteInstances_returnsEmptyList_whenInstancesDoNotExist() = runTest {
        // Act
        val removed = manager.deleteInstances(listOf(999, 888))

        // Assert
        assertEquals(0, removed.size)
    }

    @Test
    fun deleteInstances_resetsIdGenerator_whenAllInstancesDeleted() = runTest {
        // Arrange
        manager.createInstances(3)
        manager.deleteInstances(null)

        // Act
        val created = manager.createInstances(1)

        // Assert
        assertEquals(1, created[0]) // Should start from 1 again
    }

    @Test
    fun clear_removesAllInstances_andResetsIdGenerator() = runTest {
        // Arrange
        manager.createInstances(5)

        // Act
        manager.clear()

        // Assert
        assertEquals(0, manager.listInstances().size)

        // Verify ID generator reset
        val created = manager.createInstances(1)
        assertEquals(1, created[0])
    }

    @Test
    fun createInstances_usesConfigProvider_whenNoConfigsProvided() = runTest {
        // Arrange
        configProvider.configToReturn = createTestConfig(url = "https://from-provider.com")

        // Act
        val created = manager.createInstances(1)

        // Assert
        val instance = manager.getInstance(created[0])
        assertNotNull(instance)
        assertEquals("https://from-provider.com", instance.config.fachdienstUrl)
        assertTrue(configProvider.loadConfigCalled)
    }

    @Test
    fun createInstances_callsMergeWithEnvFallback_whenConfigsProvided() = runTest {
        // Arrange
        val config = createTestConfig(url = "https://original.com")
        configProvider.mergedConfigToReturn = config.copy(fachdienstUrl = "https://merged.com")

        // Act
        manager.createInstances(1, listOf(config))

        // Assert
        assertTrue(configProvider.mergeWithEnvFallbackCalled)
    }

    @Test
    fun createInstances_appliesGeneratedTokenPerInstance() = runTest {
        // Arrange
        val perCard = LoadInstanceManager(configProvider) { "token-for-${it.smbKeystoreFile}" }
        val configs = listOf(
            createTestConfig(id = 1).copy(smbKeystoreFile = "/k1.p12"),
            createTestConfig(id = 2).copy(smbKeystoreFile = "/k2.p12"),
        )

        // Act
        perCard.createInstances(2, configs)

        // Assert
        assertEquals("token-for-/k1.p12", perCard.getInstance(1)!!.config.poppToken)
        assertEquals("token-for-/k2.p12", perCard.getInstance(2)!!.config.poppToken)
        perCard.clear()
    }

    @Test
    fun createInstances_keepsStaticToken_whenNoGeneratedToken() = runTest {
        // Arrange
        val config = createTestConfig(id = 7).copy(poppToken = "static")

        // Act
        manager.createInstances(1, listOf(config))

        // Assert
        assertEquals("static", manager.getInstance(7)!!.config.poppToken)
    }

    // Helper functions
    private fun createTestConfig(
        id: Int? = null,
        url: String = "https://test.example.com",
    ) = SdkInstanceConfig(
        id = id,
        fachdienstUrl = url,
        smbKeystoreFile = "",
        smbKeystoreB64 = "123",
        smbKeystoreAlias = "1232",
        smbKeystorePassword = "123",
        aslProdEnv = true,
        poppToken = "",
        requiredOid = "",
    )
}

// Fake ConfigProvider
class FakeConfigProvider : ConfigProvider {
    var loadConfigCalled = false
    var mergeWithEnvFallbackCalled = false
    var configToReturn: SdkInstanceConfig = createDefaultConfig()
    var mergedConfigToReturn: SdkInstanceConfig? = null

    override fun loadConfig(id: Int): SdkInstanceConfig {
        loadConfigCalled = true
        return configToReturn
    }

    override fun mergeWithEnvFallback(provided: SdkInstanceConfig): SdkInstanceConfig {
        mergeWithEnvFallbackCalled = true
        return mergedConfigToReturn ?: provided
    }

    private fun createDefaultConfig() = SdkInstanceConfig(
        fachdienstUrl = "https://default.example.com",
        smbKeystoreFile = "",
        smbKeystoreB64 = "123",
        smbKeystoreAlias = "123",
        smbKeystorePassword = "123",
        aslProdEnv = true,
        poppToken = "",
        requiredOid = "",
    )
}
