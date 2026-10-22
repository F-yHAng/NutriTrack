package com.example.nutritrack.agent

import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.FruitNutritionFailure
import com.example.nutritrack.data.FruitNutritionGateway
import com.example.nutritrack.data.FruitNutritionResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AgentToolDispatcher(
    private val registry: AgentToolRegistry,
    private val repository: AgentRepository,
    private val readDataSource: AgentReadDataSource,
    private val fruitNutritionGateway: FruitNutritionGateway,
    private val clock: Clock
) {
    internal fun decode(invocation: AgentToolInvocation): DecodedAgentToolCall =
        registry.decode(invocation)

    suspend fun dispatch(
        context: AgentContext,
        invocation: AgentToolInvocation
    ): AgentDispatchResult {
        try {
            repository.requireRunningRun(context)
        } catch (error: AgentRepositoryException) {
            return AgentDispatchResult.Rejected(error.toContextError())
        }

        val decoded = try {
            decode(invocation)
        } catch (rejection: AgentToolRejection) {
            val callId = recordRejectedCall(context, rejection)
            return AgentDispatchResult.Rejected(rejection.code, callId)
        }

        return dispatch(context, decoded)
    }

    internal suspend fun dispatch(
        context: AgentContext,
        decoded: DecodedAgentToolCall
    ): AgentDispatchResult {
        try {
            repository.requireRunningRun(context)
        } catch (error: AgentRepositoryException) {
            return AgentDispatchResult.Rejected(error.toContextError())
        }

        val call = repository.recordToolCall(
            context = context,
            toolName = decoded.toolName,
            argumentsJson = decoded.canonicalArgumentsJson,
            startedAtEpochMillis = clock.millis()
        )
        return try {
            val result = execute(context.userId, decoded.input)
            val resultJson = AgentAuditJson.encode(result)
            repository.finishToolCall(
                context = context,
                callId = call.id,
                status = AgentToolCallStatus.SUCCEEDED,
                resultJson = resultJson,
                errorCode = null,
                finishedAtEpochMillis = clock.millis()
            )
            AgentDispatchResult.Success(
                result = result,
                toolCallId = call.id,
                canonicalArgumentsJson = decoded.canonicalArgumentsJson,
                canonicalResultJson = resultJson
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                repository.finishToolCall(
                    context = context,
                    callId = call.id,
                    status = AgentToolCallStatus.CANCELLED,
                    resultJson = null,
                    errorCode = AgentToolErrorCode.TOOL_CANCELLED,
                    finishedAtEpochMillis = clock.millis()
                )
            }
            throw cancelled
        } catch (failure: AgentToolExecutionFailure) {
            repository.finishToolCall(
                context = context,
                callId = call.id,
                status = AgentToolCallStatus.FAILED,
                resultJson = null,
                errorCode = failure.code,
                finishedAtEpochMillis = clock.millis()
            )
            AgentDispatchResult.Failure(failure.code, call.id)
        } catch (_: Exception) {
            repository.finishToolCall(
                context = context,
                callId = call.id,
                status = AgentToolCallStatus.FAILED,
                resultJson = null,
                errorCode = AgentToolErrorCode.UNKNOWN_FAILURE,
                finishedAtEpochMillis = clock.millis()
            )
            AgentDispatchResult.Failure(AgentToolErrorCode.UNKNOWN_FAILURE, call.id)
        }
    }

    private suspend fun recordRejectedCall(
        context: AgentContext,
        rejection: AgentToolRejection
    ): String {
        val call = repository.recordToolCall(
            context = context,
            toolName = rejection.auditedToolName,
            argumentsJson = rejection.canonicalArgumentsJson,
            startedAtEpochMillis = clock.millis()
        )
        repository.finishToolCall(
            context = context,
            callId = call.id,
            status = AgentToolCallStatus.FAILED,
            resultJson = null,
            errorCode = rejection.code,
            finishedAtEpochMillis = clock.millis()
        )
        return call.id
    }

    private suspend fun execute(userId: Int, input: AgentToolInput): AgentToolResult = when (input) {
        AgentToolInput.GetNutritionProfile -> {
            val profile = readDataSource.getNutritionProfile(userId)
                ?: throw AgentToolExecutionFailure(AgentToolErrorCode.PROFILE_NOT_FOUND)
            AgentToolResult.NutritionProfile(profile)
        }

        is AgentToolInput.GetIntakeHistory -> AgentToolResult.IntakeHistory(
            readDataSource.getIntakeHistory(userId, input.startDate, input.endDate)
        )

        is AgentToolInput.CalculateNutritionTrend -> AgentToolResult.NutritionTrend(
            NutritionTrendCalculator.calculate(
                readDataSource.getIntakeHistory(userId, input.startDate, input.endDate)
            )
        )

        is AgentToolInput.SearchFruitNutrition -> when (
            val result = fruitNutritionGateway.search(input.query)
        ) {
            is FruitNutritionResult.Found -> AgentToolResult.FruitNutrition(
                name = result.fruit.name,
                family = result.fruit.family,
                calories = result.fruit.calories,
                fat = result.fruit.fat,
                sugar = result.fruit.sugar,
                carbohydrates = result.fruit.carbohydrates,
                protein = result.fruit.protein
            )

            FruitNutritionResult.Empty -> AgentToolResult.FruitNotFound
            is FruitNutritionResult.Failure -> throw AgentToolExecutionFailure(
                result.reason.toAgentError()
            )
        }
    }

    private fun FruitNutritionFailure.toAgentError(): AgentToolErrorCode = when (this) {
        FruitNutritionFailure.TIMEOUT -> AgentToolErrorCode.FRUIT_TIMEOUT
        FruitNutritionFailure.NO_NETWORK -> AgentToolErrorCode.FRUIT_NO_NETWORK
        FruitNutritionFailure.SERVICE_UNAVAILABLE -> AgentToolErrorCode.FRUIT_SERVICE_UNAVAILABLE
        FruitNutritionFailure.INVALID_RESPONSE -> AgentToolErrorCode.FRUIT_INVALID_RESPONSE
        FruitNutritionFailure.REQUEST_REJECTED -> AgentToolErrorCode.FRUIT_REQUEST_REJECTED
        FruitNutritionFailure.UNKNOWN -> AgentToolErrorCode.UNKNOWN_FAILURE
    }

    private fun AgentRepositoryException.toContextError(): AgentToolErrorCode = when (reason) {
        AgentRepositoryError.RUN_NOT_ACTIVE -> AgentToolErrorCode.RUN_NOT_ACTIVE
        else -> AgentToolErrorCode.CONTEXT_DENIED
    }
}

internal object AgentAuditJson {
    fun encode(result: AgentToolResult): String = when (result) {
        is AgentToolResult.NutritionProfile -> JsonObject().apply {
            add("selectedFoodCategories", JsonArray().apply {
                result.profile.selectedFoodCategories.forEach(::add)
            })
            addProperty("persona", result.profile.persona)
            addProperty("mainMealTime", result.profile.mainMealTime)
            addProperty("sleepTime", result.profile.sleepTime)
            addProperty("wakeTime", result.profile.wakeTime)
            addProperty("overallHeifaScore", result.profile.overallHeifaScore)
            addProperty("fruitHeifaScore", result.profile.fruitHeifaScore)
            addProperty("vegetableHeifaScore", result.profile.vegetableHeifaScore)
        }.toString()

        is AgentToolResult.IntakeHistory -> JsonObject().apply {
            add("days", JsonArray().apply {
                result.days.forEach { day -> add(day.toJson()) }
            })
        }.toString()

        is AgentToolResult.NutritionTrend -> JsonObject().apply {
            addProperty("observedDays", result.trend.observedDays)
            addProperty(
                "averageFruitVegetableServings",
                result.trend.averageFruitVegetableServings
            )
            addProperty("averageWaterMl", result.trend.averageWaterMl)
            addProperty("averageSugarG", result.trend.averageSugarG)
            addProperty("fruitVegetableDirection", result.trend.fruitVegetableDirection.name)
        }.toString()

        is AgentToolResult.FruitNutrition -> JsonObject().apply {
            addProperty("name", result.name)
            addProperty("family", result.family)
            addProperty("calories", result.calories)
            addProperty("fat", result.fat)
            addProperty("sugar", result.sugar)
            addProperty("carbohydrates", result.carbohydrates)
            addProperty("protein", result.protein)
        }.toString()

        AgentToolResult.FruitNotFound -> "{}"
    }

    private fun AgentDailyIntake.toJson() = JsonObject().apply {
        addProperty("date", date.toString())
        addProperty("recordCount", recordCount)
        addProperty("vegetablesServings", vegetablesServings)
        addProperty("fruitServings", fruitServings)
        addProperty("grainsServings", grainsServings)
        addProperty("meatServings", meatServings)
        addProperty("dairyServings", dairyServings)
        addProperty("discretionaryServings", discretionaryServings)
        addProperty("sodiumMg", sodiumMg)
        addProperty("alcoholStandardDrinks", alcoholStandardDrinks)
        addProperty("waterMl", waterMl)
        addProperty("sugarG", sugarG)
        addProperty("saturatedFatG", saturatedFatG)
        addProperty("unsaturatedFatG", unsaturatedFatG)
    }
}
