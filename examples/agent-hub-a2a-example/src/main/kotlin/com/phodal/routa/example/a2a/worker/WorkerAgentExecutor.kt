package com.phodal.routa.example.a2a.worker

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.example.a2a.hub.HUB_CARD_PATH
import com.phodal.routa.example.a2a.hub.HUB_PATH
import com.phodal.routa.example.a2a.hub.HUB_PORT
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A2A AgentExecutor for Worker agents.
 *
 * Workers receive task instructions via A2A, execute the work (using Koog LLM),
 * and report completion back to the Agent Hub.
 *
 * ## Message Format
 *
 * The worker expects messages with the following JSON format:
 * ```json
 * {
 *   "taskId": "task-123",
 *   "agentId": "worker-agent-id",
 *   "instruction": "Implement the payment module..."
 * }
 * ```
 *
 * Or plain text for simple queries.
 *
 * ## Flow
 * ```
 * Planner → Worker (A2A)
 *   1. Receives instruction
 *   2. Processes with LLM (optional)
 *   3. Reports to Hub
 *   4. Returns result
 * ```
 */
class WorkerAgentExecutor : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val userMessage = context.params.message
        val messageText = userMessage.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }

        val steps = mutableListOf<String>()

        try {
            // Try to parse as structured task
            val (taskId, agentId, instruction) = parseInstruction(messageText)

            steps.add("Worker received task:")
            steps.add("  Task ID: ${taskId ?: "none"}")
            steps.add("  Agent ID: ${agentId ?: "none"}")
            steps.add("  Instruction: ${instruction.take(100)}")
            steps.add("")

            // Execute the work (use LLM if available)
            steps.add("Processing...")
            val workResult = executeWork(instruction)
            steps.add("✓ Work result: ${workResult.take(200)}")

            // Report to Agent Hub if we have task/agent info
            if (taskId != null && agentId != null) {
                steps.add("")
                steps.add("Reporting to Agent Hub...")
                try {
                    reportToHub(context.contextId, agentId, taskId, workResult)
                    steps.add("✓ Reported completion to hub")
                } catch (e: Exception) {
                    steps.add("⚠ Could not report to hub: ${e.message}")
                }
            }

            steps.add("")
            steps.add("✅ Task completed")

        } catch (e: Exception) {
            steps.add("✗ Error: ${e.message}")
        }

        val responseMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(steps.joinToString("\n"))),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(responseMessage)
    }

    /**
     * Parse instruction from message text.
     * Supports both JSON format and plain text.
     */
    private fun parseInstruction(text: String): Triple<String?, String?, String> {
        return try {
            val json = Json.parseToJsonElement(text).jsonObject
            val taskId = json["taskId"]?.jsonPrimitive?.contentOrNull
            val agentId = json["agentId"]?.jsonPrimitive?.contentOrNull
            val instruction = json["instruction"]?.jsonPrimitive?.content ?: text
            Triple(taskId, agentId, instruction)
        } catch (e: Exception) {
            Triple(null, null, text)
        }
    }

    /**
     * Execute work using LLM or simple processing.
     */
    private suspend fun executeWork(instruction: String): String {
        val config = RoutaConfigLoader.getActiveModelConfig()
        if (config != null) {
            return executeWithLLM(instruction, config)
        }

        // Fallback: simple echo-based processing
        return "Processed: $instruction — Implementation would go here. " +
            "Files analyzed, changes planned, tests considered."
    }

    /**
     * Use Koog LLM to process the instruction.
     */
    private suspend fun executeWithLLM(
        instruction: String,
        config: com.phodal.routa.core.config.NamedModelConfig
    ): String {
        return try {
            val executor = RoutaAgentFactory.createExecutor(config)
            val model = RoutaAgentFactory.createModel(config)

            val prompt = ai.koog.prompt.dsl.prompt("worker-task") {
                system {
                    +"""You are a worker agent. Execute the given task and provide a concise report.
                       |Include what you would do, files you would modify, and any considerations.
                       |Keep your response under 200 words.""".trimMargin()
                }
                user {
                    +instruction
                }
            }

            val result = executor.execute(prompt, model)
            result.firstOrNull()?.content ?: "Task completed (no LLM output)"
        } catch (e: Exception) {
            "Task completed with fallback (LLM error: ${e.message})"
        }
    }

    /**
     * Report completion to the Agent Hub via A2A.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun reportToHub(
        contextId: String,
        agentId: String,
        taskId: String,
        summary: String
    ) {
        val transport = HttpJSONRPCClientTransport(
            url = "http://localhost:$HUB_PORT$HUB_PATH"
        )
        val resolver = UrlAgentCardResolver(
            baseUrl = "http://localhost:$HUB_PORT",
            path = HUB_CARD_PATH,
        )
        val client = A2AClient(transport = transport, agentCardResolver = resolver)

        try {
            client.connect()

            val command = buildJsonObject {
                put("command", "report_to_parent")
                put("agentId", agentId)
                put("taskId", taskId)
                put("summary", summary.take(500))
                put("success", true)
                putJsonArray("filesModified") { add("worker-output.txt") }
            }

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(command.toString())),
                contextId = contextId,
            )
            client.sendMessage(Request(MessageSendParams(message = message)))
        } finally {
            transport.close()
        }
    }
}
