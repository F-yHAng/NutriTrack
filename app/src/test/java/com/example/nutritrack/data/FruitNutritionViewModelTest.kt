package com.example.nutritrack.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FruitNutritionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun blankInput_exposesErrorWithoutCallingGateway() = runTest {
        val gateway = FakeFruitNutritionGateway()
        val viewModel = FruitNutritionViewModel(gateway)

        viewModel.queryChanged("   ")
        viewModel.search()

        assertEquals(0, gateway.requests)
        assertEquals(
            FruitSearchStatus.Error("Enter a fruit name"),
            viewModel.uiState.value.status
        )
    }

    @Test
    fun search_exposesLoadingThenSuccessAndIgnoresDuplicateClick() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fruit = sampleFruit()
        val gateway = FakeFruitNutritionGateway(
            result = FruitNutritionResult.Found(fruit),
            gate = gate
        )
        val viewModel = FruitNutritionViewModel(gateway)

        viewModel.queryChanged(" apple ")
        viewModel.search()
        viewModel.search()
        runCurrent()

        assertEquals(1, gateway.requests)
        assertEquals(FruitSearchStatus.Loading, viewModel.uiState.value.status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(FruitSearchStatus.Success(fruit), viewModel.uiState.value.status)
        assertEquals(listOf("apple"), gateway.queries)
    }

    @Test
    fun emptyResult_exposesStableEmptyState() = runTest {
        val viewModel = FruitNutritionViewModel(
            FakeFruitNutritionGateway(FruitNutritionResult.Empty)
        )
        viewModel.queryChanged("missing")

        viewModel.search()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.status is FruitSearchStatus.Empty)
    }

    @Test
    fun gatewayFailures_exposeUserSafeMessagesForEveryFailureType() = runTest {
        FruitNutritionFailure.entries.forEach { failure ->
            val viewModel = FruitNutritionViewModel(
                FakeFruitNutritionGateway(FruitNutritionResult.Failure(failure))
            )
            viewModel.queryChanged("apple")

            viewModel.search()
            advanceUntilIdle()

            val message = (viewModel.uiState.value.status as FruitSearchStatus.Error).message
            assertTrue(message.isNotBlank())
            assertTrue("Technical detail leaked: $message", "Exception" !in message && "HTTP" !in message)
        }
    }

    @Test
    fun failureDoesNotRetryUntilUserRequestsIt() = runTest {
        val gateway = SequencedFruitNutritionGateway(
            ArrayDeque(
                listOf(
                    FruitNutritionResult.Failure(FruitNutritionFailure.TIMEOUT),
                    FruitNutritionResult.Found(sampleFruit())
                )
            )
        )
        val viewModel = FruitNutritionViewModel(gateway)
        viewModel.queryChanged("apple")

        viewModel.search()
        advanceUntilIdle()
        assertEquals(1, gateway.requests)

        viewModel.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.requests)
        assertTrue(viewModel.uiState.value.status is FruitSearchStatus.Success)
    }

    private class FakeFruitNutritionGateway(
        private val result: FruitNutritionResult = FruitNutritionResult.Empty,
        private val gate: CompletableDeferred<Unit>? = null
    ) : FruitNutritionGateway {
        var requests = 0
        val queries = mutableListOf<String>()

        override suspend fun search(name: String): FruitNutritionResult {
            requests++
            queries += name
            gate?.await()
            return result
        }
    }

    private class SequencedFruitNutritionGateway(
        private val results: ArrayDeque<FruitNutritionResult>
    ) : FruitNutritionGateway {
        var requests = 0

        override suspend fun search(name: String): FruitNutritionResult {
            requests++
            return results.removeFirst()
        }
    }

    private fun sampleFruit() = FruitNutrition(
        name = "Apple",
        family = "Rosaceae",
        calories = 52.0,
        fat = 0.4,
        sugar = 10.3,
        carbohydrates = 11.4,
        protein = 0.3
    )
}
