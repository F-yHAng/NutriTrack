package com.example.nutritrack.data

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

internal interface FruityViceApiService {
    @GET("fruit/{name}")
    suspend fun getFruitByName(@Path("name") name: String): Response<FruityViceFruitDto>
}

internal data class FruityViceFruitDto(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("family") val family: String?,
    @SerializedName("nutritions") val nutritions: FruityViceNutritionsDto?
)

internal data class FruityViceNutritionsDto(
    @SerializedName("calories") val calories: Double?,
    @SerializedName("fat") val fat: Double?,
    @SerializedName("sugar") val sugar: Double?,
    @SerializedName("carbohydrates") val carbohydrates: Double?,
    @SerializedName("protein") val protein: Double?
)
