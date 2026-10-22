package com.example.nutritrack

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FruitNetworkBoundaryTest {
    @Test
    fun uiAndActivities_doNotCreateClientsOrCallFruityViceApi() {
        val forbiddenTokens = listOf(
            "Retrofit.Builder",
            "OkHttpClient.Builder",
            "FruityViceApiService",
            "getFruitByName(",
            "lifecycleScope.launch"
        )

        productionPackageDirectory()
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .forEach { file ->
                val source = file.readText()
                forbiddenTokens.forEach { token ->
                    assertFalse("${file.name} crossed the fruit network boundary: $token", token in source)
                }
            }
    }

    @Test
    fun retrofitAndOkHttp_areBuiltOnlyByDedicatedFruitNetworkFactory() {
        val dataDirectory = File(productionPackageDirectory(), "data")
        val builders = dataDirectory
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .filter { file ->
                val source = file.readText()
                "Retrofit.Builder" in source || "OkHttpClient.Builder" in source
            }

        assertTrue("Expected exactly one dedicated network builder", builders.size == 1)
        assertTrue(builders.single().name == "FruityViceFruitNutritionGateway.kt")
    }

    private fun productionPackageDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val moduleDirectory = if (File(workingDirectory, "src/main/java").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
        return File(
            moduleDirectory,
            "src/main/java/com/example/nutritrack"
        ).also { directory -> assertTrue(directory.isDirectory) }
    }
}
