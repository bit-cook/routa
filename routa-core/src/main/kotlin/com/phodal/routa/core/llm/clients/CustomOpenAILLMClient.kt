package com.phodal.routa.core.llm.clients

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIBaseLLMResponse
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIBaseLLMStreamResponse
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIResponseFormat
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIStreamToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * Configuration settings for custom OpenAI-compatible APIs (like GLM, Qwen, Kimi, MiniMax, etc.)
 *
 * Adapted from xiuper's CustomOpenAIClientSettings.
 *
 * IMPORTANT: baseUrl MUST end with "/" for correct URL joining in Ktor.
 *
 * @property baseUrl The base URL of the custom OpenAI-compatible API
 * @property chatCompletionsPath The path for chat completions endpoint (default: "chat/completions", NO leading slash)
 * @property timeoutConfig Configuration for connection timeouts
 */
class CustomOpenAIClientSettings(
    baseUrl: String,
    chatCompletionsPath: String = "chat/completions",
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
) : OpenAIBaseSettings(baseUrl, chatCompletionsPath, timeoutConfig)

/**
 * Request model for custom OpenAI-compatible chat completion
 */
@Serializable
data class CustomOpenAIChatCompletionRequest(
    val messages: List<OpenAIMessage>,
    val model: String,
    val frequencyPenalty: Double? = null,
    val logprobs: Boolean? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val responseFormat: OpenAIResponseFormat? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val toolChoice: OpenAIToolChoice? = null,
    val tools: List<OpenAITool>? = null,
    val topLogprobs: Int? = null,
    val topP: Double? = null,
)

/**
 * Response model for custom OpenAI-compatible chat completion
 */
@Serializable
data class CustomOpenAIChatCompletionResponse(
    override val id: String,
    val `object`: String? = null,
    override val created: Long = 0L,
    override val model: String = "",
    val choices: List<Choice>,
    val usage: OpenAIUsage? = null,
) : OpenAIBaseLLMResponse {
    @Serializable
    data class Choice(
        val index: Int,
        val message: OpenAIMessage.Assistant,
        val finishReason: String? = null,
    )
}

/**
 * Streaming response model for custom OpenAI-compatible chat completion
 */
@Serializable
data class CustomOpenAIChatCompletionStreamResponse(
    override val id: String,
    val `object`: String? = null,
    override val created: Long = 0L,
    override val model: String = "",
    val choices: List<StreamChoice>,
    val usage: OpenAIUsage? = null,
) : OpenAIBaseLLMStreamResponse {
    @Serializable
    data class StreamChoice(
        val index: Int,
        val delta: Delta,
        val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        val toolCalls: List<OpenAIStreamToolCall>? = null,
    )
}

/**
 * Implementation of LLMClient for custom OpenAI-compatible APIs.
 *
 * This client can be used with any OpenAI-compatible API like GLM, Qwen, Kimi, MiniMax,
 * GitHub Copilot, or any custom endpoint.
 *
 * Adapted from xiuper's CustomOpenAILLMClient for Koog 0.6.2 API.
 *
 * **IMPORTANT URL Construction in Ktor**:
 * - `path` starting with `/` -> Ktor treats as absolute, DISCARDS baseUrl path
 * - `path` NOT starting with `/` -> Ktor appends to baseUrl (correct)
 *
 * @param apiKey The API key for the custom API
 * @param baseUrl The base URL (e.g., "https://open.bigmodel.cn/api/paas/v4/")
 * @param chatCompletionsPath Path for chat completions (default: "chat/completions", NO leading slash)
 * @param customHeaders Custom HTTP headers to include in requests
 */
class CustomOpenAILLMClient(
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String = "chat/completions",
    private val customHeaders: Map<String, String> = emptyMap(),
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(),
    baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System,
) : AbstractOpenAILLMClient<CustomOpenAIChatCompletionResponse, CustomOpenAIChatCompletionStreamResponse>(
    apiKey,
    CustomOpenAIClientSettings(baseUrl, chatCompletionsPath, timeoutConfig),
    baseClient.config {
        if (customHeaders.isNotEmpty()) {
            install(io.ktor.client.plugins.DefaultRequest) {
                customHeaders.forEach { (key, value) ->
                    headers.append(key, value)
                }
            }
        }
    },
    clock,
    staticLogger,
    OpenAICompatibleToolDescriptorSchemaGenerator(),
) {

    private companion object {
        private val staticLogger = KotlinLogging.logger { }

        init {
            registerOpenAIJsonSchemaGenerators(LLMProvider.OpenAI)
        }
    }

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean,
    ): String {
        val responseFormat = createResponseFormat(params.schema, model)
        val effectiveTools = tools?.takeIf { it.isNotEmpty() }
        val effectiveToolChoice = if (effectiveTools != null) toolChoice else null

        val request = CustomOpenAIChatCompletionRequest(
            messages = messages,
            model = model.id,
            responseFormat = responseFormat,
            stream = stream,
            temperature = params.temperature,
            toolChoice = effectiveToolChoice,
            tools = effectiveTools,
        )

        return json.encodeToString(request)
    }

    override fun processProviderChatResponse(response: CustomOpenAIChatCompletionResponse): List<LLMChoice> {
        require(response.choices.isNotEmpty()) { "Empty choices in response" }
        return response.choices.map {
            it.message.toMessageResponses(it.finishReason, createMetaInfo(response.usage))
        }
    }

    override fun decodeStreamingResponse(data: String): CustomOpenAIChatCompletionStreamResponse =
        json.decodeFromString(data)

    override fun decodeResponse(data: String): CustomOpenAIChatCompletionResponse =
        json.decodeFromString(data)

    override fun processStreamingResponse(
        response: Flow<CustomOpenAIChatCompletionStreamResponse>,
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        var finishReason: String? = null
        var metaInfo: ai.koog.prompt.message.ResponseMetaInfo? = null

        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content?.let { emitAppend(it) }

                choice.delta.toolCalls?.forEach { openAIToolCall ->
                    upsertToolCall(
                        openAIToolCall.index,
                        openAIToolCall.id,
                        openAIToolCall.function?.name,
                        openAIToolCall.function?.arguments,
                    )
                }

                choice.finishReason?.let { finishReason = it }
            }

            chunk.usage?.let { metaInfo = createMetaInfo(it) }
        }

        emitEnd(finishReason, metaInfo)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        throw UnsupportedOperationException("Moderation is not supported by custom OpenAI-compatible APIs.")
    }
}
