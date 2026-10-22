package com.example.nutritrack.data

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.PermissionMissingException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import com.google.firebase.ai.type.ServiceDisabledException
import java.io.IOException
import kotlinx.coroutines.CancellationException

class FirebaseAiGateway(
    private val isAppCheckInitialized: () -> Boolean,
    private val modelName: String = AiModelConfig.DEFAULT_MODEL_NAME
) : AiGateway {
    private val generativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(modelName)
    }

    override suspend fun generate(request: AiRequest): AiResult {
        if (!isAppCheckInitialized()) {
            return AiResult.Failure(AiError.ServiceConfigurationPending)
        }

        return try {
            val text = generativeModel.generateContent(request.prompt).text
            if (text.isNullOrBlank()) {
                AiResult.Failure(AiError.EmptyResponse)
            } else {
                AiResult.Success(AiResponse(text = text, modelName = modelName))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RequestTimeoutException) {
            AiResult.Failure(AiError.Timeout)
        } catch (_: ServiceConnectionHandshakeFailedException) {
            AiResult.Failure(AiError.Network)
        } catch (_: IOException) {
            AiResult.Failure(AiError.Network)
        } catch (_: APINotConfiguredException) {
            AiResult.Failure(AiError.ServiceConfigurationPending)
        } catch (_: ServiceDisabledException) {
            AiResult.Failure(AiError.ServiceConfigurationPending)
        } catch (_: PermissionMissingException) {
            AiResult.Failure(AiError.ServiceConfigurationPending)
        } catch (_: InvalidAPIKeyException) {
            AiResult.Failure(AiError.ServiceConfigurationPending)
        } catch (_: FirebaseAIException) {
            AiResult.Failure(AiError.Sdk)
        }
    }
}
