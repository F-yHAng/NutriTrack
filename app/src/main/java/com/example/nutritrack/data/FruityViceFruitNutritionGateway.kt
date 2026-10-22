package com.example.nutritrack.data

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

internal class FruityViceFruitNutritionGateway(
    private val service: FruityViceApiService
) : FruitNutritionGateway {
    override suspend fun search(name: String): FruitNutritionResult {
        return try {
            val response = service.getFruitByName(name)
            when {
                response.code() == 404 -> FruitNutritionResult.Empty
                response.code() >= 500 -> failure(FruitNutritionFailure.SERVICE_UNAVAILABLE)
                !response.isSuccessful -> failure(FruitNutritionFailure.REQUEST_REJECTED)
                response.body() == null -> FruitNutritionResult.Empty
                else -> mapBody(checkNotNull(response.body()))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SocketTimeoutException) {
            failure(FruitNutritionFailure.TIMEOUT)
        } catch (_: UnknownHostException) {
            failure(FruitNutritionFailure.NO_NETWORK)
        } catch (_: NoRouteToHostException) {
            failure(FruitNutritionFailure.NO_NETWORK)
        } catch (_: ConnectException) {
            failure(FruitNutritionFailure.NO_NETWORK)
        } catch (_: JsonParseException) {
            failure(FruitNutritionFailure.INVALID_RESPONSE)
        } catch (_: MalformedJsonException) {
            failure(FruitNutritionFailure.INVALID_RESPONSE)
        } catch (_: EOFException) {
            failure(FruitNutritionFailure.INVALID_RESPONSE)
        } catch (_: IOException) {
            failure(FruitNutritionFailure.NO_NETWORK)
        } catch (_: Exception) {
            failure(FruitNutritionFailure.UNKNOWN)
        }
    }

    private fun mapBody(dto: FruityViceFruitDto): FruitNutritionResult = try {
        require((dto.id ?: 0) > 0) { "Invalid fruit identifier" }
        val nutrition = requireNotNull(dto.nutritions) { "Missing nutrition data" }
        FruitNutritionResult.Found(
            FruitNutrition(
                name = dto.name.requiredText(),
                family = dto.family.requiredText(),
                calories = nutrition.calories.requiredNutritionValue(),
                fat = nutrition.fat.requiredNutritionValue(),
                sugar = nutrition.sugar.requiredNutritionValue(),
                carbohydrates = nutrition.carbohydrates.requiredNutritionValue(),
                protein = nutrition.protein.requiredNutritionValue()
            )
        )
    } catch (_: IllegalArgumentException) {
        failure(FruitNutritionFailure.INVALID_RESPONSE)
    }

    private fun String?.requiredText(): String = requireNotNull(this)
        .trim()
        .also { require(it.isNotEmpty()) { "Missing fruit text" } }

    private fun Double?.requiredNutritionValue(): Double = requireNotNull(this)
        .also { require(it.isFinite() && it >= 0.0) { "Invalid nutrition value" } }

    private fun failure(reason: FruitNutritionFailure) = FruitNutritionResult.Failure(reason)
}

internal object FruitNutritionNetwork {
    private const val BASE_URL = "https://fruityvice.com/api/"
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 10L
    private const val CALL_TIMEOUT_SECONDS = 15L

    fun createGateway(): FruitNutritionGateway {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val service = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FruityViceApiService::class.java)
        return FruityViceFruitNutritionGateway(service)
    }
}
