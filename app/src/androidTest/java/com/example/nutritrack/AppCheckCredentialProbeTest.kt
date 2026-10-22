package com.example.nutritrack

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Default-off probe for the target Debug application's App Check credential exchange.
 *
 * This test deliberately uses [FirebaseApp.getInstance] from the target application's process and
 * never creates a generative model, calls Firebase AI, reads an App Check token, or writes token
 * content to logs, assertions, or persistent storage.
 */
@RunWith(AndroidJUnit4::class)
class AppCheckCredentialProbeTest {

    @Test
    fun forceRefresh_targetAppCredentialExchangeRequiresExplicitOptIn() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true"
        )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as? NutrackApplication
            ?: throw AssertionError("event=FAIL stage=TARGET_APPLICATION reason=UNEXPECTED_APPLICATION")
        val firebaseApp = FirebaseApp.getInstance()
        val appCheck = FirebaseAppCheck.getInstance(firebaseApp)

        // Firebase AI's Firebase.ai(backend = ...) also defaults to FirebaseApp.getInstance().
        assertEquals(targetContext.packageName, application.packageName)
        assertEquals(application.packageName, firebaseApp.applicationContext.packageName)
        assertEquals(DEFAULT_FIREBASE_APP_NAME, firebaseApp.name)
        assertSame(appCheck, FirebaseAppCheck.getInstance())

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val tokenHandle = Tasks.await(
                appCheck.getAppCheckToken(/* forceRefresh = */ true),
                EXCHANGE_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            )
            // Do not access tokenHandle.token or tokenHandle.expireTimeMillis.
            assertNotNull(tokenHandle)
            Log.i(
                TAG,
                "event=SUCCESS stage=APP_CHECK_TOKEN_EXCHANGE forceRefresh=true " +
                    "firebaseApp=DEFAULT elapsedMillis=${SystemClock.elapsedRealtime() - startedAt}"
            )
        } catch (error: Throwable) {
            val summary = safeFailureSummary(
                error = error,
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt
            )
            Log.i(TAG, summary)
            // Do not retain the original Throwable: its message can contain provider-generated data.
            throw AssertionError(summary)
        }
    }

    private fun safeFailureSummary(error: Throwable, elapsedMillis: Long): String {
        val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val publicMessage = causes.firstNotNullOfOrNull { it.message }
        val normalizedMessage = publicMessage.orEmpty().lowercase()
        val messageSource = if (publicMessage == null) "ABSENT" else "PUBLIC_EXCEPTION_MESSAGE"
        val messageReason = classifyMessageReason(normalizedMessage)
        val messageHttpStatus = extractHttpStatus(normalizedMessage) ?: "NOT_EXPOSED_BY_SDK"
        val causeTypes = causes.drop(1).joinToString(",") { it.javaClass.simpleName }.ifBlank { "NONE" }

        return "event=FAIL stage=APP_CHECK_TOKEN_EXCHANGE forceRefresh=true " +
            "elapsedMillis=$elapsedMillis exception=${error.javaClass.simpleName} " +
            "causeTypes=$causeTypes messagePresent=${publicMessage != null} " +
            "messageSource=$messageSource messageReason=$messageReason " +
            "httpStatus=$messageHttpStatus httpStatusSource=" +
            if (messageHttpStatus == "NOT_EXPOSED_BY_SDK") "SDK_PUBLIC_API" else "PUBLIC_EXCEPTION_MESSAGE"
    }

    private fun classifyMessageReason(message: String): String = when {
        message.contains("no appcheckprovider") || message.contains("no app check provider") ->
            "PROVIDER_NOT_INSTALLED"
        message.contains("debug") && message.contains("token") -> "DEBUG_PROVIDER"
        message.contains("app check") -> "APP_CHECK"
        message.contains("permission") || message.contains("unauthorized") || message.contains("forbidden") ->
            "PERMISSION"
        message.contains("quota") || message.contains("rate limit") -> "QUOTA"
        message.contains("timeout") || message.contains("timed out") -> "TIMEOUT"
        message.contains("network") || message.contains("connect") || message.contains("resolve") -> "NETWORK"
        message.contains("not configured") || message.contains("disabled") -> "CONFIGURATION"
        message.isBlank() -> "UNCLASSIFIED"
        else -> "UNCLASSIFIED"
    }

    private fun extractHttpStatus(message: String): String? =
        HTTP_STATUS_PATTERN.find(message)?.groupValues?.getOrNull(1)

    private companion object {
        const val ENABLE_ARGUMENT = "enableAppCheckCredentialProbe"
        const val TAG = "NutrackAppCheckProbe"
        const val DEFAULT_FIREBASE_APP_NAME = "[DEFAULT]"
        const val EXCHANGE_TIMEOUT_MILLIS = 65_000L
        const val MAX_CAUSE_DEPTH = 3
        val HTTP_STATUS_PATTERN = Regex("\\b(?:http\\s*)?(?:status(?:\\s*code)?|code)\\s*[:=]?\\s*([1-5]\\d\\d)\\b")
    }
}
