package com.example.nutritrack

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDataBoundaryTest {
    @Test
    fun dataScreens_onlyRenderStateAndDispatchCallbacks() {
        val screens = mapOf(
            "LoginActivity.kt" to "LoginScreen",
            "RegisterScreen.kt" to "RegisterScreen",
            "QuestionnaireActivity.kt" to "QuestionnaireScreen",
            "HomeActivity.kt" to "HomeScreen",
            "InsightsScreen.kt" to "InsightsScreen",
            "DailyIntakeScreen.kt" to "DailyIntakeScreen",
            "NutriCoachScreen.kt" to "NutriCoachScreen",
            "ClinicianScreen.kt" to "ClinicianScreen",
            "SettingsScreen.kt" to "SettingsScreen"
        )
        val forbiddenTokens = listOf(
            "NutrackApplication",
            ".repository",
            "Dao",
            "lifecycleScope",
            "rememberCoroutineScope",
            "CoroutineScope("
        )

        screens.forEach { (fileName, functionName) ->
            val source = sourceFile(fileName).readText()
            val body = functionBody(source, functionName)
            forbiddenTokens.forEach { token ->
                assertFalse(
                    "$functionName must not access data sources or launch business coroutines: $token",
                    token in body
                )
            }
        }
    }

    private fun sourceFile(fileName: String): File {
        val workingDirectory = File(System.getProperty("user.dir").orEmpty())
        val moduleDirectory = if (File(workingDirectory, "src/main/java").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
        return File(
            moduleDirectory,
            "src/main/java/com/example/nutritrack/$fileName"
        ).also { file -> assertTrue("Missing production source ${file.path}", file.isFile) }
    }

    private fun functionBody(source: String, functionName: String): String {
        val signatureStart = source.indexOf("fun $functionName(")
        assertTrue("Missing $functionName", signatureStart >= 0)
        // Skip the whole parameter list: a default callback can itself contain braces.
        var parameterDepth = 0
        var signatureEnd = -1
        for (index in source.indexOf('(', signatureStart) until source.length) {
            when (source[index]) {
                '(' -> parameterDepth += 1
                ')' -> {
                    parameterDepth -= 1
                    if (parameterDepth == 0) {
                        signatureEnd = index
                        break
                    }
                }
            }
        }
        assertTrue("Unclosed parameters for $functionName", signatureEnd >= 0)
        val bodyStart = source.indexOf('{', signatureEnd)
        assertTrue("Missing body for $functionName", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("Unclosed body for $functionName")
    }
}
