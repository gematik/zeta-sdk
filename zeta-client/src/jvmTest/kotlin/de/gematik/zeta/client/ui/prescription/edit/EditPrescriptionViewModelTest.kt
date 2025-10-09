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

package de.gematik.zeta.client.ui.prescription.edit

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
class EditPrescriptionViewModelTest {

    private lateinit var viewModel: EditPrescriptionViewModel
    private lateinit var mockRepository: PrescriptionRepository
    private lateinit var testScope: TestScope

    private val testPrescriptionId = 1L

    @BeforeTest
    fun setUp() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        testScope = TestScope(testDispatcher)
        mockRepository = mockk()

        viewModel = EditPrescriptionViewModel(testScope.contextualBackgroundScope(), mockRepository)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is FormUpdated with empty model`() = testScope.runTest {
        val initialState = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(initialState)
        assertEquals(PrescriptionModel(), initialState.result)
    }

    @Test
    fun `loadPrescription success updates state with FormUpdated`() = testScope.runTest {
        val expectedModel = PrescriptionModel(id = testPrescriptionId, medicationName = "MedLoaded")
        coEvery { mockRepository.prescription(testPrescriptionId) } returns expectedModel

        viewModel.loadPrescription(testPrescriptionId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(state)
        assertEquals(expectedModel, state.result)
        coVerify { mockRepository.prescription(testPrescriptionId) }
    }

    @Test
    fun `loadPrescription with null id throws error and updates state to Error`() = testScope.runTest {
        val idForNullModel = -1L
        val expectedModelForNullId = PrescriptionModel(id = idForNullModel, medicationName = "MedX")
        coEvery { mockRepository.prescription(idForNullModel) } returns expectedModelForNullId

        viewModel.loadPrescription(null)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(state)
        assertEquals(expectedModelForNullId, state.result)
        coVerify { mockRepository.prescription(idForNullModel) }
    }

    @Test
    fun `loadPrescription failure updates state to Error`() = testScope.runTest {
        val errorMessage = "Network Error"
        coEvery { mockRepository.prescription(testPrescriptionId) } throws RuntimeException(errorMessage)

        viewModel.loadPrescription(testPrescriptionId)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<MviState.Error>(state)
        assertEquals(errorMessage, state.error)
        coVerify { mockRepository.prescription(testPrescriptionId) }
    }

    @Test
    fun `updateForm updates state with new model`() = testScope.runTest {
        val updatedModel = PrescriptionModel(id = testPrescriptionId, medicationName = "MedX", dosage = "10mg")
        viewModel.updateForm(updatedModel)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(state)
        assertEquals(updatedModel, state.result)
    }

    @Test
    fun `savePrescription success updates state to Saved and clears form`() = testScope.runTest {
        val initialModel = PrescriptionModel(id = testPrescriptionId, medicationName = "InitMed")
        coEvery { mockRepository.prescription(testPrescriptionId) } returns initialModel

        viewModel.loadPrescription(testPrescriptionId)
        advanceUntilIdle()

        val modelToSave = initialModel.copy(medicationName = "UpdatedMed")
        viewModel.updateForm(modelToSave)
        advanceUntilIdle()

        coEvery { mockRepository.updatePrescription(testPrescriptionId, modelToSave) } returns Unit

        viewModel.savePrescription()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<EditPrescriptionState.Saved>(state)
        coVerify { mockRepository.updatePrescription(testPrescriptionId, modelToSave) }

        viewModel.clearForm()
        advanceUntilIdle()

        val formStateAfterSaveAndClear = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(formStateAfterSaveAndClear)
        assertEquals(
            PrescriptionModel(),
            formStateAfterSaveAndClear.result,
            "Internal model should be cleared after save",
        )
    }

    @Test
    fun `savePrescription failure updates state to Error`() = testScope.runTest {
        val initialModel = PrescriptionModel(id = testPrescriptionId, medicationName = "ErrMed")
        coEvery { mockRepository.prescription(testPrescriptionId) } returns initialModel
        viewModel.loadPrescription(testPrescriptionId)
        advanceUntilIdle()

        val modelToSave = initialModel.copy(medicationName = "AttemptedSaveMed")
        viewModel.updateForm(modelToSave)
        advanceUntilIdle()

        val errorMessage = "Failed to save"
        coEvery { mockRepository.updatePrescription(testPrescriptionId, modelToSave) } throws
            RuntimeException(errorMessage)

        viewModel.savePrescription()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<MviState.Error>(state)
        assertEquals(errorMessage, state.error)
        coVerify { mockRepository.updatePrescription(testPrescriptionId, modelToSave) }
    }

    @Test
    fun `clearForm resets model and updates state to FormUpdated`() = testScope.runTest {
        val initialLoadedModel = PrescriptionModel(id = testPrescriptionId, medicationName = "InitMed")
        coEvery { mockRepository.prescription(testPrescriptionId) } returns initialLoadedModel

        viewModel.loadPrescription(testPrescriptionId)
        advanceUntilIdle()

        viewModel.updateForm(initialLoadedModel.copy(medicationName = "ModifiedMed"))
        advanceUntilIdle()
        assertNotEquals(
            PrescriptionModel(),
            viewModel.state.value.let {
                (it as EditPrescriptionState.FormUpdated).result
            },
        )

        viewModel.clearForm()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<EditPrescriptionState.FormUpdated>(state)
        assertEquals(PrescriptionModel(), state.result, "Model should be reset to default")
    }
}
