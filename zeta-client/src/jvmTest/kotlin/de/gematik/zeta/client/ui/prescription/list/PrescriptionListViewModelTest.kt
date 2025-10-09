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

package de.gematik.zeta.client.ui.prescription.list

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
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class PrescriptionListViewModelTest {

    private lateinit var viewModel: PrescriptionListViewModel
    private lateinit var testScope: TestScope

    private lateinit var mockRepository: PrescriptionRepository

    @BeforeTest
    fun setUp() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        testScope = TestScope(testDispatcher)
        mockRepository = mockk()

        viewModel = PrescriptionListViewModel(testScope.contextualBackgroundScope(), mockRepository)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Result with empty list`() {
        val initialState = viewModel.state.value
        assertTrue(initialState is PrescriptionListState.Result, "Initial state should be Result")
        assertTrue(initialState.result.isEmpty(), "Initial result list should be empty")
    }

    @Test
    fun `loadPrescriptionList success updates state to Result with prescriptions`() = testScope.runTest {
        val prescriptions = listOf(
            PrescriptionModel(id = 1, medicationName = "MedA"),
            PrescriptionModel(id = 2, medicationName = "MedB"),
        )
        coEvery { mockRepository.prescriptionList() } returns prescriptions

        viewModel.loadPrescriptionList()
        advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is PrescriptionListState.Result)
        assertEquals(prescriptions, currentState.result)
        assertTrue(viewModel.state.value is PrescriptionListState.Result)
        assertEquals(prescriptions, (viewModel.state.value as PrescriptionListState.Result).result)
    }

    @Test
    fun `loadPrescriptionList failure updates state to Error`() = testScope.runTest {
        val errorMessage = "Network error"

        coEvery { mockRepository.prescriptionList() } throws RuntimeException(errorMessage)

        viewModel.loadPrescriptionList()
        advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is MviState.Error)
        assertEquals(errorMessage, currentState.error)
    }

    @Test
    fun `deletePrescription success refreshes list and updates state to Result`() = testScope.runTest {
        val initialPrescriptions = listOf(
            PrescriptionModel(id = 1, medicationName = "MedA"),
            PrescriptionModel(id = 2, medicationName = "MedB"),
        )
        val prescriptionToDelete = initialPrescriptions.first()
        val remainingPrescriptions = listOf(initialPrescriptions.last())

        coEvery { mockRepository.prescriptionList() } returns remainingPrescriptions
        coEvery { mockRepository.deletePrescription(prescriptionToDelete.id!!) } returns Unit

        viewModel.deletePrescription(prescriptionToDelete)
        advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is PrescriptionListState.Result)
        assertEquals(remainingPrescriptions, currentState.result)
        coVerify { mockRepository.deletePrescription(prescriptionToDelete.id!!) }
        coVerify(exactly = 1) { mockRepository.prescriptionList() }
    }

    @Test
    fun `deletePrescription failure updates state to Error`() = testScope.runTest {
        val prescriptionToDelete = PrescriptionModel(id = 1, medicationName = "MedA")
        val errorMessage = "Deletion failed"

        coEvery { mockRepository.deletePrescription(prescriptionToDelete.id!!) } throws RuntimeException(errorMessage)
        coEvery { mockRepository.prescriptionList() } throws RuntimeException(errorMessage)

        viewModel.deletePrescription(prescriptionToDelete)
        advanceUntilIdle()

        val currentState = viewModel.state.value
        assertTrue(currentState is MviState.Error)
        assertEquals(errorMessage, currentState.error)
    }
}
