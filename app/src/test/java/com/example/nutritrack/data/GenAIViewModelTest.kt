package com.example.nutritrack.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenAIViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun successfulRequest_exposesTrimmedTextAndModelName() = runTest {
        val gateway = FakeAiGateway(
            result = AiResult.Success(AiResponse("  generated guidance  ", "fake-model"))
        )
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("  nutrition prompt  ")
        advanceUntilIdle()

        assertEquals(AiRequest("nutrition prompt"), gateway.requests.single())
        assertEquals(UiState.Success("generated guidance", "fake-model"), viewModel.uiState.value)
    }

    @Test
    fun emptyResponse_exposesExplicitError() = runTest {
        val gateway = FakeAiGateway(
            result = AiResult.Success(AiResponse("   ", "fake-model"))
        )
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("prompt")
        advanceUntilIdle()

        assertEquals(UiState.Error("No response received from AI"), viewModel.uiState.value)
    }

    @Test
    fun networkAndSdkFailures_exposeStableMessages() = runTest {
        val gateway = FakeAiGateway(result = AiResult.Failure(AiError.Network))
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("prompt")
        advanceUntilIdle()
        assertEquals(
            UiState.Error("Unable to reach AI service. Check your connection and try again."),
            viewModel.uiState.value
        )

        gateway.result = AiResult.Failure(AiError.Sdk)
        viewModel.sendPrompt("prompt")
        advanceUntilIdle()
        assertEquals(UiState.Error("AI service error. Please try again."), viewModel.uiState.value)
    }

    @Test
    fun pendingServiceConfiguration_exposesRecoverableError() = runTest {
        val gateway = FakeAiGateway(
            result = AiResult.Failure(AiError.ServiceConfigurationPending)
        )
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("prompt")
        advanceUntilIdle()

        assertEquals(
            UiState.Error("服务配置待完成，请完成 App Check 注册后重试。"),
            viewModel.uiState.value
        )
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun timeout_exposesErrorAndCancelsGatewayWork() = runTest {
        val gateway = FakeAiGateway(delayMillis = Long.MAX_VALUE)
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("prompt")
        runCurrent()
        assertEquals(UiState.Loading, viewModel.uiState.value)

        advanceTimeBy(GenAIViewModel.REQUEST_TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(UiState.Error("AI request timed out. Please try again."), viewModel.uiState.value)
    }

    @Test
    fun cancellation_exposesExplicitState() = runTest {
        val gateway = FakeAiGateway(gate = CompletableDeferred())
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("prompt")
        runCurrent()
        viewModel.cancelRequest()
        runCurrent()

        assertEquals(UiState.Error("AI request cancelled."), viewModel.uiState.value)
    }

    @Test
    fun repeatedRequestWhileLoading_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeAiGateway(gate = gate)
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("first")
        runCurrent()
        viewModel.sendPrompt("second")
        runCurrent()

        assertEquals(1, gateway.requests.size)
        assertEquals(UiState.Loading, viewModel.uiState.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is UiState.Success)
    }

    @Test
    fun blankPrompt_isRejectedWithoutCallingGateway() = runTest {
        val gateway = FakeAiGateway()
        val viewModel = GenAIViewModel(gateway)

        viewModel.sendPrompt("   ")
        advanceUntilIdle()

        assertEquals(UiState.Error("Please enter a prompt."), viewModel.uiState.value)
        assertTrue(gateway.requests.isEmpty())
    }

    private class FakeAiGateway(
        var result: AiResult = AiResult.Success(AiResponse("generated", "fake-model")),
        var gate: CompletableDeferred<Unit>? = null,
        var delayMillis: Long = 0L
    ) : AiGateway {
        val requests = mutableListOf<AiRequest>()

        override suspend fun generate(request: AiRequest): AiResult {
            requests += request
            gate?.await()
            if (delayMillis > 0) delay(delayMillis)
            return result
        }
    }
}
