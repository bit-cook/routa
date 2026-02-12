package com.phodal.routa.example.a2a.hub

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.ModelTier
import com.phodal.routa.core.model.Task
import com.phodal.routa.core.tool.AgentTools
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A2A AgentExecutor that bridges the A2A protocol to routa-agent-hub's AgentTools.
 *
 * Receives JSON-formatted commands via A2A messages and delegates to the appropriate
 * [AgentTools] method. This allows any A2A client to manage agents through the hub.
 *
 * ## Supported Commands
 *
 * ```json
 * {"command": "list_agents"}
 * {"command": "create_agent", "name": "...", "role": "CRAFTER", "parentId": "..."}
 * {"command": "get_agent_status", "agentId": "..."}
 * {"command": "get_agent_summary", "agentId": "..."}
 * {"command": "read_agent_conversation", "agentId": "...", "lastN": 10}
 * {"command": "send_message", "fromAgentId": "...", "toAgentId": "...", "message": "..."}
 * {"command": "delegate_task", "agentId": "...", "taskId": "...", "callerAgentId": "..."}
 * {"command": "report_to_parent", "agentId": "...", "taskId": "...", "summary": "...", "success": true}
 * {"command": "wake_or_create_task_agent", "taskId": "...", "contextMessage": "...", "callerAgentId": "..."}
 * {"command": "send_message_to_task_agent", "taskId": "...", "message": "...", "callerAgentId": "..."}
 * {"command": "subscribe_to_events", "agentId": "...", "eventTypes": ["agent:*"]}
 * {"command": "unsubscribe_from_events", "subscriptionId": "..."}
 * {"command": "create_task", "title": "...", "objective": "..."}
 * {"command": "initialize", "workspaceId": "..."}
 * ```
 */
class AgentHubExecutor(
    private val system: RoutaSystem,
    private val defaultWorkspaceId: String,
) : AgentExecutor {

    private val agentTools: AgentTools get() = system.tools

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val userMessage = context.params.message
        val messageText = userMessage.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }

        // Parse the command JSON
        val result = try {
            val json = Json.parseToJsonElement(messageText).jsonObject
            val command = json["command"]?.jsonPrimitive?.content
                ?: return sendError(context, eventProcessor, "Missing 'command' field")

            executeCommand(command, json)
        } catch (e: Exception) {
            "Error: ${e.message}\n\nExpected JSON format: {\"command\": \"<command_name>\", ...args}"
        }

        // Send result back as A2A message
        val responseMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(result)),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(responseMessage)
    }

    private suspend fun executeCommand(command: String, args: JsonObject): String {
        return when (command) {
            "initialize" -> {
                val workspaceId = args.str("workspaceId") ?: defaultWorkspaceId
                val routaId = system.coordinator.initialize(workspaceId)
                buildJsonObject {
                    put("success", true)
                    put("routaAgentId", routaId)
                    put("workspaceId", workspaceId)
                }.toString()
            }

            "list_agents" -> {
                val workspaceId = args.str("workspaceId") ?: defaultWorkspaceId
                val result = agentTools.listAgents(workspaceId)
                formatToolResult(result)
            }

            "create_agent" -> {
                val name = args.str("name") ?: return error("Missing 'name'")
                val roleStr = args.str("role") ?: return error("Missing 'role'")
                val role = try { AgentRole.valueOf(roleStr.uppercase()) }
                catch (e: Exception) { return error("Invalid role: $roleStr") }
                val parentId = args.str("parentId")
                val modelTierStr = args.str("modelTier")
                val modelTier = modelTierStr?.let {
                    try { ModelTier.valueOf(it.uppercase()) } catch (e: Exception) { null }
                }

                val result = agentTools.createAgent(
                    name = name,
                    role = role,
                    workspaceId = defaultWorkspaceId,
                    parentId = parentId,
                    modelTier = modelTier,
                )
                formatToolResult(result)
            }

            "get_agent_status" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val result = agentTools.getAgentStatus(agentId)
                formatToolResult(result)
            }

            "get_agent_summary" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val result = agentTools.getAgentSummary(agentId)
                formatToolResult(result)
            }

            "read_agent_conversation" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val lastN = args.int("lastN")
                val includeToolCalls = args.bool("includeToolCalls") ?: true
                val result = agentTools.readAgentConversation(
                    agentId = agentId,
                    lastN = lastN,
                    includeToolCalls = includeToolCalls,
                )
                formatToolResult(result)
            }

            "send_message" -> {
                val fromAgentId = args.str("fromAgentId") ?: return error("Missing 'fromAgentId'")
                val toAgentId = args.str("toAgentId") ?: return error("Missing 'toAgentId'")
                val message = args.str("message") ?: return error("Missing 'message'")
                val result = agentTools.messageAgent(fromAgentId, toAgentId, message)
                formatToolResult(result)
            }

            "delegate_task" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val taskId = args.str("taskId") ?: return error("Missing 'taskId'")
                val callerAgentId = args.str("callerAgentId") ?: return error("Missing 'callerAgentId'")
                val result = agentTools.delegate(agentId, taskId, callerAgentId)
                formatToolResult(result)
            }

            "report_to_parent" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val taskId = args.str("taskId") ?: return error("Missing 'taskId'")
                val summary = args.str("summary") ?: return error("Missing 'summary'")
                val filesModified = args["filesModified"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val success = args.bool("success") ?: true

                val report = CompletionReport(
                    agentId = agentId,
                    taskId = taskId,
                    summary = summary,
                    filesModified = filesModified,
                    success = success,
                )
                val result = agentTools.reportToParent(agentId, report)
                formatToolResult(result)
            }

            "wake_or_create_task_agent" -> {
                val taskId = args.str("taskId") ?: return error("Missing 'taskId'")
                val contextMessage = args.str("contextMessage") ?: return error("Missing 'contextMessage'")
                val callerAgentId = args.str("callerAgentId") ?: return error("Missing 'callerAgentId'")
                val agentName = args.str("agentName")
                val modelTier = args.str("modelTier")?.let {
                    try { ModelTier.valueOf(it.uppercase()) } catch (e: Exception) { null }
                }

                val result = agentTools.wakeOrCreateTaskAgent(
                    taskId = taskId,
                    contextMessage = contextMessage,
                    callerAgentId = callerAgentId,
                    workspaceId = defaultWorkspaceId,
                    agentName = agentName,
                    modelTier = modelTier,
                )
                formatToolResult(result)
            }

            "send_message_to_task_agent" -> {
                val taskId = args.str("taskId") ?: return error("Missing 'taskId'")
                val message = args.str("message") ?: return error("Missing 'message'")
                val callerAgentId = args.str("callerAgentId") ?: return error("Missing 'callerAgentId'")
                val result = agentTools.sendMessageToTaskAgent(taskId, message, callerAgentId)
                formatToolResult(result)
            }

            "subscribe_to_events" -> {
                val agentId = args.str("agentId") ?: return error("Missing 'agentId'")
                val agentName = args.str("agentName") ?: ""
                val eventTypes = args["eventTypes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("*")
                val excludeSelf = args.bool("excludeSelf") ?: true
                val result = agentTools.subscribeToEvents(agentId, agentName, eventTypes, excludeSelf)
                formatToolResult(result)
            }

            "unsubscribe_from_events" -> {
                val subscriptionId = args.str("subscriptionId") ?: return error("Missing 'subscriptionId'")
                val result = agentTools.unsubscribeFromEvents(subscriptionId)
                formatToolResult(result)
            }

            "create_task" -> {
                val title = args.str("title") ?: return error("Missing 'title'")
                val objective = args.str("objective") ?: return error("Missing 'objective'")
                val taskId = args.str("taskId") ?: "task-${System.currentTimeMillis()}"
                val task = Task(
                    id = taskId,
                    title = title,
                    objective = objective,
                    workspaceId = defaultWorkspaceId,
                    createdAt = Instant.now().toString(),
                    updatedAt = Instant.now().toString(),
                )
                system.context.taskStore.save(task)
                buildJsonObject {
                    put("success", true)
                    put("taskId", taskId)
                    put("title", title)
                }.toString()
            }

            else -> error("Unknown command: $command")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun sendError(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
        message: String
    ) {
        val errorMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart("Error: $message")),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(errorMessage)
    }

    private fun formatToolResult(result: com.phodal.routa.core.tool.ToolResult): String {
        return if (result.success) {
            result.data
        } else {
            buildJsonObject {
                put("success", false)
                put("error", result.error ?: "Unknown error")
            }.toString()
        }
    }

    private fun error(msg: String): String = buildJsonObject {
        put("success", false)
        put("error", msg)
    }.toString()

    // Extension helpers for JSON parsing
    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
}
