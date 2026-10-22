package com.example.nutritrack.data

data class FruitNutrition(
    val name: String,
    val family: String,
    val calories: Double,
    val fat: Double,
    val sugar: Double,
    val carbohydrates: Double,
    val protein: Double
)

sealed interface FruitNutritionResult {
    data class Found(val fruit: FruitNutrition) : FruitNutritionResult
    data object Empty : FruitNutritionResult
    data class Failure(val reason: FruitNutritionFailure) : FruitNutritionResult
}

enum class FruitNutritionFailure {
    TIMEOUT,
    NO_NETWORK,
    SERVICE_UNAVAILABLE,
    INVALID_RESPONSE,
    REQUEST_REJECTED,
    UNKNOWN
}

fun interface FruitNutritionGateway {
    suspend fun search(name: String): FruitNutritionResult
}
