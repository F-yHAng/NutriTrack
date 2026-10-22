package com.example.nutritrack.data

import com.google.gson.JsonSyntaxException
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class FruityViceFruitNutritionGatewayTest {
    @Test
    fun successfulResponse_mapsDtoToStableDomainModel() = runTest {
        val gateway = gatewayReturning(Response.success(validDto()))

        val result = gateway.search("apple")

        val fruit = (result as FruitNutritionResult.Found).fruit
        assertEquals("Apple", fruit.name)
        assertEquals("Rosaceae", fruit.family)
        assertEquals(52.0, fruit.calories, 0.0)
    }

    @Test
    fun emptyBodyAndNotFound_mapToEmpty() = runTest {
        val emptyBody = gatewayReturning(Response.success<FruityViceFruitDto>(null))
        val notFound = gatewayReturning(errorResponse(404))

        assertEquals(FruitNutritionResult.Empty, emptyBody.search("apple"))
        assertEquals(FruitNutritionResult.Empty, notFound.search("missing"))
    }

    @Test
    fun clientAndServerErrors_areSeparatedWithoutLeakingHttpDetails() = runTest {
        val clientError = gatewayReturning(errorResponse(400))
        val serverError = gatewayReturning(errorResponse(503))

        assertEquals(
            FruitNutritionResult.Failure(FruitNutritionFailure.REQUEST_REJECTED),
            clientError.search("apple")
        )
        assertEquals(
            FruitNutritionResult.Failure(FruitNutritionFailure.SERVICE_UNAVAILABLE),
            serverError.search("apple")
        )
    }

    @Test
    fun missingOrIllegalNutritionValues_mapToInvalidResponse() = runTest {
        val missing = gatewayReturning(Response.success(validDto().copy(nutritions = null)))
        val negative = gatewayReturning(
            Response.success(
                validDto().copy(
                    nutritions = validDto().nutritions?.copy(calories = -1.0)
                )
            )
        )

        assertEquals(invalidResponse(), missing.search("apple"))
        assertEquals(invalidResponse(), negative.search("apple"))
    }

    @Test
    fun transportAndParsingFailures_mapToExplicitFailureTypes() = runTest {
        val cases = listOf(
            SocketTimeoutException() to FruitNutritionFailure.TIMEOUT,
            UnknownHostException() to FruitNutritionFailure.NO_NETWORK,
            IOException("offline") to FruitNutritionFailure.NO_NETWORK,
            JsonSyntaxException("bad json") to FruitNutritionFailure.INVALID_RESPONSE,
            EOFException("truncated json") to FruitNutritionFailure.INVALID_RESPONSE,
            IllegalStateException("unexpected") to FruitNutritionFailure.UNKNOWN
        )

        cases.forEach { (exception, expected) ->
            val gateway = FruityViceFruitNutritionGateway(
                FakeFruityViceApiService { throw exception }
            )
            assertEquals(
                FruitNutritionResult.Failure(expected),
                gateway.search("apple")
            )
        }
    }

    private fun gatewayReturning(
        response: Response<FruityViceFruitDto>
    ) = FruityViceFruitNutritionGateway(FakeFruityViceApiService { response })

    private fun validDto() = FruityViceFruitDto(
        id = 6,
        name = "Apple",
        family = "Rosaceae",
        nutritions = FruityViceNutritionsDto(
            calories = 52.0,
            fat = 0.4,
            sugar = 10.3,
            carbohydrates = 11.4,
            protein = 0.3
        )
    )

    private fun errorResponse(code: Int): Response<FruityViceFruitDto> = Response.error(
        code,
        "{}".toResponseBody("application/json".toMediaType())
    )

    private fun invalidResponse() =
        FruitNutritionResult.Failure(FruitNutritionFailure.INVALID_RESPONSE)

    private fun interface FakeFruityViceApiService : FruityViceApiService {
        suspend fun response(name: String): Response<FruityViceFruitDto>

        override suspend fun getFruitByName(name: String): Response<FruityViceFruitDto> =
            response(name)
    }
}
