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

package de.gematik.zeta.client.ui.prescription.add

import com.ensody.reactivestate.test.contextualBackgroundScope
import de.gematik.zeta.client.data.repository.PrescriptionRepository
import de.gematik.zeta.client.model.PrescriptionModel
import de.gematik.zeta.client.ui.common.mvi.MviState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AddPrescriptionViewModelTest {

    private lateinit var mockRepository: PrescriptionRepository
    private lateinit var testScope: TestScope

    private lateinit var viewModel: AddPrescriptionViewModel

    @BeforeTest
    fun setUp() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        testScope = TestScope(testDispatcher)
        mockRepository = mockk()

        viewModel = AddPrescriptionViewModel(testScope.contextualBackgroundScope(), mockRepository)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is FormUpdated with empty model`() = testScope.runTest {
        val initialState = viewModel.state.value
        assertIs<AddPrescriptionState.FormUpdated>(initialState)
        assertEquals(PrescriptionModel(), initialState.result)
    }

    @Test
    fun `updatePrescription updates state with FormUpdated`() = testScope.runTest {
        val newModel = PrescriptionModel(id = 1, medicationName = "MedX")

        viewModel.updatePrescription(newModel)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<AddPrescriptionState.FormUpdated>(state)
        assertEquals(newModel, state.result)
    }

    @Test
    fun `clearForm resets model and updates state to FormUpdated with empty model`() = testScope.runTest {
        viewModel.updatePrescription(PrescriptionModel(medicationName = "MedX"))
        advanceUntilIdle()

        assertNotEquals(
            PrescriptionModel(),
            viewModel.state.value.let {
                (it as AddPrescriptionState.FormUpdated).result
            },
        )

        viewModel.clearForm()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<AddPrescriptionState.FormUpdated>(state)
        assertEquals(PrescriptionModel(), state.result)
    }

    @Test
    fun `savePrescription success updates state to Added and clears form`() = testScope.runTest {
        val modelToSave = PrescriptionModel(medicationName = "MedX")

        viewModel.updatePrescription(modelToSave)
        advanceUntilIdle()

        coEvery { mockRepository.addPrescription(modelToSave) } returns Unit

        viewModel.savePrescription()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<AddPrescriptionState.Added>(state, "State should be Added after successful save")

        coVerify { mockRepository.addPrescription(modelToSave) }

        viewModel.clearForm()
        advanceUntilIdle()

        val formStateAfterSaveAndClear = viewModel.state.value
        assertIs<AddPrescriptionState.FormUpdated>(formStateAfterSaveAndClear)
        assertEquals(
            PrescriptionModel(),
            formStateAfterSaveAndClear.result,
            "Internal model should be cleared after save",
        )
    }

    @Test
    fun `savePrescription failure updates state to Error`() = testScope.runTest {
        val modelToSave = PrescriptionModel(medicationName = "MedX")

        viewModel.updatePrescription(modelToSave)
        advanceUntilIdle()

        val errorMessage = "Network Error"
        coEvery { mockRepository.addPrescription(modelToSave) } throws RuntimeException(errorMessage)

        viewModel.savePrescription()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<MviState.Error>(state)
        assertEquals(errorMessage, state.error)
        coVerify { mockRepository.addPrescription(modelToSave) }
    }
}
