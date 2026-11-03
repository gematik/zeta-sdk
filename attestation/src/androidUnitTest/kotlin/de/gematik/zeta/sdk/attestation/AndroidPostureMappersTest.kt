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

import android.app.admin.DevicePolicyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPostureMappersTest {
    @Test
    fun passwordComplexity_mapsToSchemaValues() {
        assertEquals(0, mapPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_NONE))
        assertEquals(1, mapPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_LOW))
        assertEquals(2, mapPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM))
        assertEquals(3, mapPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH))
        assertNull(mapPasswordComplexity(42))
    }

    @Test
    fun storageEncryption_activeStatesAreEncrypted() {
        assertTrue(isStorageEncrypted(DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE))
        assertTrue(isStorageEncrypted(DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER))
        assertFalse(isStorageEncrypted(DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE))
        assertFalse(isStorageEncrypted(DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED))
    }
}
