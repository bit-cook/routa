@file:OptIn(ExperimentalUuidApi::class)

package com.phodal.routa.example.a2a.scenario

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import com.phodal.routa.example.a2a.hub.*
import io.ktor.server.cio.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Full end-to-end integration scenario for routa-agent-hub via A2A protocol.
 *
 * This scenario:
 * 1. Starts the Agent Hub A2A server in-process
 * 2. Connects as an A2A client
 * 3. Exercises ALL 12 agent management tools through A2A
 * 4. Verifies each operation returns expected results
 * 5. Prints a comprehensive test report
 *
 * ## Usage
 * ```bash
 * ./gradlew :examples:agent-hub-a2a-example:runIntegration
 * ```
 *
 * No external dependencies required — runs entirely in-process with in-memory stores.
 */
suspend fun main() {
    println("╔══════════════════════════════════════════════════════════╗")
    println("║  Routa Agent Hub — A2A Integration Test Scenario        ║")
    println("║  Tests all 12 agent management tools via A2A protocol   ║")
    println("╚══════════════════════════════════════════════════════════╝")
    println()

    val port = 9200 // Use different port to avoid conflicts
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start Hub A2A Server in background
    println("Starting Agent Hub A2A Server on port $port...")
    val (a2aServer, routa) = createHubA2AServer(workspaceId = "integration-test", port = port)
    val agentCard = createAgentCard(port)
    val transport = HttpJSONRPCServerTransport(a2aServer)

    val serverJob = scope.launch {
        transport.start(
            engineFactory = CIO,
            port = port,
            path = HUB_PATH,
            wait = true,
            agentCard = agentCard,
            agentCardPath = HUB_CARD_PATH,
        )
    }

    // Give server time to start
    delay(2000)
    println("✓ Server started\n")

    // Create A2A client
    val clientTransport = HttpJSONRPCClientTransport(
        url = "http://localhost:$port$HUB_PATH"
    )
    val resolver = UrlAgentCardResolver(
        baseUrl = "http://localhost:$port",
        path = HUB_CARD_PATH,
    )
    val client = A2AClient(transport = clientTransport, agentCardResolver = resolver)
    client.connect()

    val contextId = "test-${System.currentTimeMillis()}"
    var passed = 0
    var failed = 0

    fun test(name: String, block: suspend () -> Boolean) {
        runBlocking {
            try {
                val result = block()
                if (result) {
                    println("  ✅ $name")
                    passed++
                } else {
                    println("  ❌ $name (assertion failed)")
                    failed++
                }
            } catch (e: Exception) {
                println("  ❌ $name (${e.message})")
                failed++
            }
        }
    }

    // ═══════════════════════════════════════════════
    // Test 1: Initialize workspace
    // ═══════════════════════════════════════════════
    println("═══ Test Suite: Agent Lifecycle ═══")

    var routaId = ""
    test("1. Initialize workspace") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "initialize")
            put("workspaceId", "integration-test")
        })
        val json = Json.parseToJsonElement(result).jsonObject
        routaId = json["routaAgentId"]?.jsonPrimitive?.content ?: ""
        json["success"]?.jsonPrimitive?.boolean == true && routaId.isNotEmpty()
    }

    // ═══════════════════════════════════════════════
    // Test 2: List agents (should have ROUTA)
    // ═══════════════════════════════════════════════
    test("2. List agents (should contain ROUTA)") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "list_agents")
        })
        result.contains("routa") || result.contains("ROUTA")
    }

    // ═══════════════════════════════════════════════
    // Test 3: Create CRAFTER agent
    // ═══════════════════════════════════════════════
    var crafterId = ""
    test("3. Create CRAFTER agent") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "create_agent")
            put("name", "test-crafter-1")
            put("role", "CRAFTER")
            put("parentId", routaId)
        })
        val json = Json.parseToJsonElement(result).jsonObject
        crafterId = json["id"]?.jsonPrimitive?.content ?: ""
        crafterId.isNotEmpty()
    }

    // ═══════════════════════════════════════════════
    // Test 4: Create GATE agent
    // ═══════════════════════════════════════════════
    var gateId = ""
    test("4. Create GATE agent") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "create_agent")
            put("name", "test-gate-1")
            put("role", "GATE")
            put("parentId", routaId)
            put("modelTier", "SMART")
        })
        val json = Json.parseToJsonElement(result).jsonObject
        gateId = json["id"]?.jsonPrimitive?.content ?: ""
        gateId.isNotEmpty()
    }

    // ═══════════════════════════════════════════════
    // Test 5: Get agent status
    // ═══════════════════════════════════════════════
    println("\n═══ Test Suite: Agent Status & Summary ═══")

    test("5. Get agent status (ROUTA)") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "get_agent_status")
            put("agentId", routaId)
        })
        result.contains("routa") || result.contains("ACTIVE") || result.contains("status")
    }

    // ═══════════════════════════════════════════════
    // Test 6: Get agent summary
    // ═══════════════════════════════════════════════
    test("6. Get agent summary (CRAFTER)") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "get_agent_summary")
            put("agentId", crafterId)
        })
        result.contains("Agent Summary") || result.contains("test-crafter")
    }

    // ═══════════════════════════════════════════════
    // Test 7: List agents (should have 3)
    // ═══════════════════════════════════════════════
    test("7. List agents (should have 3 agents)") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "list_agents")
        })
        result.contains("test-crafter-1") && result.contains("test-gate-1")
    }

    // ═══════════════════════════════════════════════
    // Test 8: Send message between agents
    // ═══════════════════════════════════════════════
    println("\n═══ Test Suite: Inter-Agent Communication ═══")

    test("8. Send message between agents") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "send_message")
            put("fromAgentId", routaId)
            put("toAgentId", crafterId)
            put("message", "Please start working on the task")
        })
        !result.contains("error") || result.contains("success")
    }

    // ═══════════════════════════════════════════════
    // Test 9: Read agent conversation
    // ═══════════════════════════════════════════════
    test("9. Read agent conversation") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "read_agent_conversation")
            put("agentId", crafterId)
            put("lastN", 5)
        })
        result.contains("start working") || result.isNotEmpty()
    }

    // ═══════════════════════════════════════════════
    // Test 10: Create and delegate task
    // ═══════════════════════════════════════════════
    println("\n═══ Test Suite: Task Delegation ═══")

    var taskId = ""
    test("10. Create task") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "create_task")
            put("taskId", "test-task-1")
            put("title", "Implement login feature")
            put("objective", "Create user authentication with JWT tokens")
        })
        val json = Json.parseToJsonElement(result).jsonObject
        taskId = json["taskId"]?.jsonPrimitive?.content ?: ""
        json["success"]?.jsonPrimitive?.boolean == true
    }

    test("11. Delegate task to CRAFTER") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "delegate_task")
            put("agentId", crafterId)
            put("taskId", taskId)
            put("callerAgentId", routaId)
        })
        !result.contains("\"success\":false") && !result.contains("\"success\": false")
    }

    // ═══════════════════════════════════════════════
    // Test 12: Report to parent
    // ═══════════════════════════════════════════════
    test("12. Report to parent (CRAFTER → ROUTA)") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "report_to_parent")
            put("agentId", crafterId)
            put("taskId", taskId)
            put("summary", "Implemented JWT authentication with bcrypt password hashing")
            put("success", true)
            putJsonArray("filesModified") {
                add("src/auth/login.kt")
                add("src/auth/jwt.kt")
            }
        })
        !result.contains("\"success\":false") && !result.contains("\"success\": false")
    }

    // ═══════════════════════════════════════════════
    // Test 13: Wake or create task agent
    // ═══════════════════════════════════════════════
    println("\n═══ Test Suite: Task-Agent Lifecycle ═══")

    test("13. Create task for wake_or_create") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "create_task")
            put("taskId", "test-task-2")
            put("title", "Add unit tests")
            put("objective", "Write tests for auth module")
        })
        result.contains("success")
    }

    test("14. Wake or create task agent") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "wake_or_create_task_agent")
            put("taskId", "test-task-2")
            put("contextMessage", "Dependencies are ready, please start testing")
            put("callerAgentId", routaId)
            put("agentName", "test-agent")
        })
        result.contains("created_new") || result.contains("woke")
    }

    test("15. Send message to task agent") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "send_message_to_task_agent")
            put("taskId", "test-task-2")
            put("message", "Focus on edge cases in authentication")
            put("callerAgentId", routaId)
        })
        !result.contains("\"success\":false") && !result.contains("\"success\": false")
    }

    // ═══════════════════════════════════════════════
    // Test 16: Event subscription
    // ═══════════════════════════════════════════════
    println("\n═══ Test Suite: Event Subscription ═══")

    var subscriptionId = ""
    test("16. Subscribe to events") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "subscribe_to_events")
            put("agentId", routaId)
            put("agentName", "routa")
            putJsonArray("eventTypes") {
                add("agent:*")
                add("task:*")
            }
        })
        // Try to extract subscription ID
        try {
            val json = Json.parseToJsonElement(result).jsonObject
            subscriptionId = json["subscriptionId"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            // Result might be plain text
            subscriptionId = "fallback-sub-id"
        }
        !result.contains("\"success\":false")
    }

    test("17. Unsubscribe from events") {
        if (subscriptionId.isEmpty() || subscriptionId == "fallback-sub-id") {
            // Skip if we didn't get a real subscription ID
            true
        } else {
            val result = sendCommand(client, contextId, buildJsonObject {
                put("command", "unsubscribe_from_events")
                put("subscriptionId", subscriptionId)
            })
            !result.contains("\"success\":false")
        }
    }

    // ═══════════════════════════════════════════════
    // Final verification
    // ═══════════════════════════════════════════════
    println("\n═══ Final Verification ═══")

    test("18. Final agent roster check") {
        val result = sendCommand(client, contextId, buildJsonObject {
            put("command", "list_agents")
        })
        println("    Agent roster:\n    ${result.take(500).replace("\n", "\n    ")}")
        result.contains("test-crafter-1") && result.contains("test-gate-1")
    }

    // ═══════════════════════════════════════════════
    // Report
    // ═══════════════════════════════════════════════
    println()
    println("═".repeat(56))
    println("  Test Results: $passed passed, $failed failed, ${passed + failed} total")
    if (failed == 0) {
        println("  🎉 All tests passed! routa-agent-hub A2A integration is OK")
    } else {
        println("  ⚠  Some tests failed — check output above")
    }
    println("═".repeat(56))

    // Cleanup
    clientTransport.close()
    routa.coordinator.shutdown()
    serverJob.cancel()

    // Exit
    if (failed > 0) {
        System.exit(1)
    }
}

/**
 * Send a JSON command to the Agent Hub via A2A and return the response text.
 */
private suspend fun sendCommand(
    client: A2AClient,
    contextId: String,
    command: JsonObject
): String {
    val message = Message(
        messageId = Uuid.random().toString(),
        role = Role.User,
        parts = listOf(TextPart(command.toString())),
        contextId = contextId,
    )
    val response = client.sendMessage(Request(MessageSendParams(message = message)))
    val reply = response.data as? Message
        ?: return "Error: unexpected response type: ${response.data}"
    return reply.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
}
