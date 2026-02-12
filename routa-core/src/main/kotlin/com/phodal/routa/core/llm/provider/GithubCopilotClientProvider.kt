package com.phodal.routa.core.llm.provider

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.llm.clients.CustomOpenAILLMClient
import com.phodal.routa.core.llm.provider.model.CopilotModel
import com.phodal.routa.core.llm.provider.model.CopilotModelsResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * API token with expiration for GitHub Copilot
 */
private data class CopilotApiToken(
    val token: String,
    val expiresAt: Instant,
) {
    fun isExpired(): Boolean {
        val now = Clock.System.now()
        val remainingSeconds = expiresAt.epochSeconds - now.epochSeconds
        return remainingSeconds < 5 * 60
    }
}

/**
 * JVM-only GitHub Copilot LLM Client Provider.
 *
 * Adapted from xiuper's GithubCopilotClientProvider.
 *
 * This provider integrates with GitHub Copilot by:
 * 1. Reading OAuth token from local GitHub Copilot config (~/.config/github-copilot/apps.json)
 * 2. Exchanging OAuth token for API token
 * 3. Fetching available models from /models API
 * 4. Using API token to call the OpenAI-compatible chat completions endpoint
 *
 * Usage:
 * ```kotlin
 * LLMClientRegistry.register(GithubCopilotClientProvider())
 *
 * val models = provider.fetchAvailableModelsAsync()
 *
 * val config = NamedModelConfig(provider = "github_copilot", model = "gpt-4o")
 * val executor = ExecutorFactory.createAsync(config)
 * ```
 */
class GithubCopilotClientProvider : LLMClientProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO)
    private val tokenMutex = Mutex()
    private val modelsMutex = Mutex()

    private var cachedApiToken: CopilotApiToken? = null
    private var cachedModels: List<CopilotModel>? = null
    private var modelsLastUpdated: Long = 0

    override val providerType: LLMProviderType = LLMProviderType.GITHUB_COPILOT

    override fun isAvailable(): Boolean = extractOauthToken() != null

    override suspend fun createExecutor(config: NamedModelConfig): SingleLLMPromptExecutor? {
        val apiToken = getApiToken() ?: return null

        val client = CustomOpenAILLMClient(
            apiKey = apiToken.token,
            baseUrl = COPILOT_API_BASE_URL,
            customHeaders = mapOf(
                "Editor-Version" to "Zed/Unknown",
                "Copilot-Integration-Id" to "vscode-chat",
            ),
        )

        return SingleLLMPromptExecutor(client)
    }

    override fun getAvailableModels(): List<String> {
        return cachedModels?.filter { it.isEnabled && !it.isEmbedding }?.map { it.id } ?: FALLBACK_MODELS
    }

    fun getCachedCopilotModels(): List<CopilotModel>? = cachedModels

    override suspend fun fetchAvailableModelsAsync(forceRefresh: Boolean): List<String> {
        val models = fetchModelsFromApi(forceRefresh)
        return models?.filter { it.isEnabled && !it.isEmbedding }?.map { it.id } ?: FALLBACK_MODELS
    }

    suspend fun fetchCopilotModelsAsync(forceRefresh: Boolean = false): List<CopilotModel>? {
        return fetchModelsFromApi(forceRefresh)
    }

    override fun getDefaultBaseUrl(): String = COPILOT_API_BASE_URL

    // ============= Private Implementation =============

    private suspend fun fetchModelsFromApi(forceRefresh: Boolean): List<CopilotModel>? {
        return modelsMutex.withLock {
            val currentTime = System.currentTimeMillis()

            if (!forceRefresh && cachedModels != null && (currentTime - modelsLastUpdated < CACHE_DURATION_MS)) {
                return@withLock cachedModels
            }

            val apiToken = getApiToken() ?: return@withLock cachedModels

            try {
                val response = httpClient.get(MODELS_ENDPOINT) {
                    header("Authorization", "Bearer ${apiToken.token}")
                    header("Editor-Version", "Zed/Unknown")
                    header("Content-Type", "application/json")
                    header("Copilot-Integration-Id", "vscode-chat")
                }

                if (response.status.isSuccess()) {
                    val responseBody = response.bodyAsText()
                    val modelsResponse = json.decodeFromString<CopilotModelsResponse>(responseBody)
                    val enabledModels = modelsResponse.data.filter { it.isEnabled }

                    cachedModels = enabledModels
                    modelsLastUpdated = currentTime

                    enabledModels
                } else {
                    cachedModels
                }
            } catch (_: Exception) {
                cachedModels
            }
        }
    }

    private fun extractOauthToken(): String? {
        val configDir = getConfigDir() ?: return null
        val appsFile = File(configDir, "apps.json")
        if (!appsFile.exists()) return null

        return try {
            val content = appsFile.readText()
            val jsonElement = json.parseToJsonElement(content)
            findOauthToken(jsonElement)
        } catch (_: Exception) {
            null
        }
    }

    private fun findOauthToken(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                element["oauth_token"]?.jsonPrimitive?.content?.let { return it }
                for ((_, value) in element) {
                    findOauthToken(value)?.let { return it }
                }
                null
            }
            is JsonArray -> {
                for (item in element) {
                    findOauthToken(item)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private suspend fun getApiToken(): CopilotApiToken? {
        return tokenMutex.withLock {
            cachedApiToken?.let { token ->
                if (!token.isExpired()) return@withLock token
            }

            val oauthToken = extractOauthToken() ?: return@withLock null

            try {
                val response = httpClient.get(TOKEN_ENDPOINT) {
                    header("Authorization", "token $oauthToken")
                    header("Accept", "application/json")
                }

                if (response.status.isSuccess()) {
                    val responseBody = response.bodyAsText()
                    val responseJson = json.parseToJsonElement(responseBody).jsonObject

                    val token = responseJson["token"]?.jsonPrimitive?.content
                        ?: throw IllegalStateException("Failed to parse token from response")
                    val expiresAt = responseJson["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: throw IllegalStateException("Failed to parse expires_at from response")

                    CopilotApiToken(
                        token = token,
                        expiresAt = Instant.fromEpochSeconds(expiresAt),
                    ).also { cachedApiToken = it }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getConfigDir(): String? {
        val homeDir = System.getProperty("user.home") ?: return null
        val osName = System.getProperty("os.name", "").lowercase()

        return when {
            osName.contains("windows") -> {
                val appData = System.getenv("APPDATA") ?: System.getenv("LOCALAPPDATA")
                if (appData != null) "$appData/github-copilot" else "$homeDir/AppData/Local/github-copilot"
            }
            else -> "$homeDir/.config/github-copilot"
        }
    }

    companion object {
        private const val COPILOT_API_BASE_URL = "https://api.githubcopilot.com/"
        private const val TOKEN_ENDPOINT = "https://api.github.com/copilot_internal/v2/token"
        private const val MODELS_ENDPOINT = "https://api.githubcopilot.com/models"
        private const val CACHE_DURATION_MS = 3600000L // 1 hour

        private val FALLBACK_MODELS = listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4", "claude-3.5-sonnet", "o1-preview", "o1-mini",
        )
    }
}
