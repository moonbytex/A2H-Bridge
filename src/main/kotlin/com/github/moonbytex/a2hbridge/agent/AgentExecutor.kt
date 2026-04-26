package com.github.moonbytex.a2hbridge.agent

import com.github.moonbytex.a2hbridge.api.ChatMessage
import com.github.moonbytex.a2hbridge.api.FunctionDefinition
import com.github.moonbytex.a2hbridge.api.OpenAIClient
import com.github.moonbytex.a2hbridge.api.ToolCallDefinition
import com.github.moonbytex.a2hbridge.conversation.ConversationManager
import com.github.moonbytex.a2hbridge.tools.IdeTools
import com.github.moonbytex.a2hbridge.util.ToolCallInfo
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*

data class ToolResult(
    val content: String,
    val confirmed: Boolean = true
)

class AgentExecutor(
    private val project: Project,
    private val agentType: AgentType,
    private val conversationManager: ConversationManager,
    private val onChunk: suspend (String) -> Unit,
    private val onReasoning: suspend (String) -> Unit = {},
    private val onToolCall: suspend (ToolCallInfo) -> ToolResult,
    private val onDone: suspend () -> Unit
) {
    private val client = OpenAIClient()
    private val ideTools = IdeTools(project)

    private val toolDefinitions = listOf(
        ToolCallDefinition(
            function = FunctionDefinition(
                name = "readFile",
                description = "Read the contents of a file at the specified path",
                parameters = makeSingleParam("path", "string", "The file path to read")
            )
        ),
        ToolCallDefinition(
            function = FunctionDefinition(
                name = "searchCode",
                description = "Search for code containing the specified query term in the project",
                parameters = makeSingleParam("query", "string", "The search term to find in code")
            )
        ),
        ToolCallDefinition(
            function = FunctionDefinition(
                name = "listProjectFiles",
                description = "List files and directories under the specified path",
                parameters = makeSingleParam("dir", "string", "The directory path to list")
            )
        ),
        ToolCallDefinition(
            function = FunctionDefinition(
                name = "writeCode",
                description = "Write generated code to a file in the project. Requires user confirmation.",
                parameters = makeMultiParams(
                    mapOf(
                        "path" to ("string" to "The file path to write"),
                        "content" to ("string" to "The code content to write")
                    )
                )
            )
        )
    )

    fun execute(userMessage: String): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            println("[A2H-DEBUG] AgentExecutor.execute: message=$userMessage")
            val systemMessage = ChatMessage.system(agentType.systemPrompt)
            val userMsg = ChatMessage.user(userMessage)

            conversationManager.addMessage(agentType, systemMessage)
            conversationManager.addMessage(agentType, userMsg)
            println("[A2H-DEBUG] Messages added to conversation")

            var maxTurns = 15
            while (maxTurns-- > 0) {
                val messages = conversationManager.getMessagesForAPI(agentType)
                println("[A2H-DEBUG] Turn $maxTurns, sending ${messages.size} messages to API")
                var hasToolCalls = false
                var hasContent = false
                val pendingToolCalls = mutableListOf<ToolCallInfo>()

                val response = client.streamChat(messages, toolDefinitions)
                println("[A2H-DEBUG] API response: isSuccessful=${response.isSuccessful}, code=${response.code}")

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    println("[A2H-DEBUG] API error: ${errorBody}")
                    onChunk("\n\n[API 错误 ${response.code}: $errorBody]")
                    onDone()
                    response.close()
                    break
                }

                var allRawData = ""
                response.body?.byteStream()?.bufferedReader()?.use { reader ->
                    var buffer = ""
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        buffer += line + "\n"
                        if (line!!.startsWith("data: ")) {
                            allRawData += line + "\n"
                        }
                        if (line!!.isEmpty() || line == "\r") {
                            println("[A2H-DEBUG] Parsing SSE block: ${buffer.take(500)}")
                            parseBlock(buffer)?.let { event ->
                                println("[A2H-DEBUG] Parsed event: content=${event.content?.take(50)}, reasoning=${event.reasoningContent?.take(50)}, done=${event.done}, toolCalls=${event.toolCalls?.size}")
                                processEvent(event, pendingToolCalls, onChunk, onReasoning, agentType, conversationManager)
                                if (event.done) return@use
                                if (event.toolCalls != null) hasToolCalls = true
                                if (event.content != null) hasContent = true
                            }
                            buffer = ""
                        }
                    }
                    // Process any remaining buffer
                    if (buffer.isNotEmpty()) {
                        println("[A2H-DEBUG] Processing remaining buffer: ${buffer.take(500)}")
                        parseBlock(buffer)?.let { event ->
                            println("[A2H-DEBUG] Parsed buffer event: content=${event.content?.take(50)}, reasoning=${event.reasoningContent?.take(50)}, done=${event.done}")
                            processEvent(event, pendingToolCalls, onChunk, onReasoning, agentType, conversationManager)
                        }
                    }
                    println("[A2H-DEBUG] Raw SSE data lines: ${allRawData.take(1000)}")
                    println("[A2H-DEBUG] SSE stream ended, hasToolCalls=$hasToolCalls, hasContent=$hasContent")
                }

                if (hasToolCalls) {
                    println("[A2H-DEBUG] Processing ${pendingToolCalls.size} tool calls")
                    val messageToolCalls = pendingToolCalls.map { it.toMessageToolCall() }
                    conversationManager.addMessage(
                        agentType,
                        ChatMessage.assistant("", messageToolCalls)
                    )

                    for (tc in pendingToolCalls) {
                        val result = if (tc.name == "writeCode") {
                            onToolCall(tc)
                        } else {
                            executeTool(tc)
                        }
                        println("[A2H-DEBUG] Tool ${tc.name} result: ${result.content.take(100)}")

                        conversationManager.addMessage(
                            agentType,
                            ChatMessage.tool(result.content, tc.id)
                        )
                    }
                } else {
                    println("[A2H-DEBUG] No tool calls, breaking loop")
                    onDone()
                    break
                }

                response.close()
            }

            if (maxTurns <= 0) {
                onChunk("\n\n[达到最大对话轮次限制]")
                onDone()
            }
        }
    }

    private suspend fun processEvent(
        event: SseEvent,
        pendingToolCalls: MutableList<ToolCallInfo>,
        onChunk: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        agentType: AgentType,
        conversationManager: ConversationManager
    ) {
        if (event.reasoningContent != null) {
            conversationManager.appendToLastAssistantReasoning(agentType, event.reasoningContent)
            onReasoning(event.reasoningContent)
        }
        if (event.content != null) {
            conversationManager.appendToLastAssistant(agentType, event.content)
            onChunk(event.content)
        }
        if (event.toolCalls != null && event.toolCalls.isNotEmpty()) {
            pendingToolCalls.addAll(event.toolCalls)
        }
        // Don't call onDone() here - let the main loop decide based on hasToolCalls
    }

    data class SseEvent(
        val content: String? = null,
        val reasoningContent: String? = null,
        val done: Boolean = false,
        val toolCalls: List<ToolCallInfo>? = null
    )

    private fun parseBlock(block: String): SseEvent? {
        for (line in block.lines()) {
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()
                if (data == "[DONE]") {
                    return SseEvent(done = true)
                }
                try {
                    val root = JsonParser.parseString(data).asJsonObject
                    val choices = root.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        println("[A2H-DEBUG] parseBlock: choices is null or empty")
                        return null
                    }

                    val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                    println("[A2H-DEBUG] parseBlock: delta=$delta")
                    if (delta == null) return null

                    val content = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                    val reasoningContent = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                    println("[A2H-DEBUG] parseBlock: content=$content, reasoningContent=$reasoningContent")

                    val toolCalls = delta.getAsJsonArray("tool_calls")?.mapNotNull { tc ->
                        val tcObj = tc.asJsonObject
                        val id = tcObj.get("id")?.asString ?: return@mapNotNull null
                        val funcObj = tcObj.getAsJsonObject("function") ?: return@mapNotNull null
                        val name = funcObj.get("name")?.asString ?: return@mapNotNull null
                        val args = funcObj.get("arguments")?.asString ?: "{}"
                        ToolCallInfo(id, name, args)
                    }

                    if (content != null || reasoningContent != null || toolCalls != null) {
                        return SseEvent(content = content, reasoningContent = reasoningContent, toolCalls = toolCalls)
                    } else {
                        println("[A2H-DEBUG] parseBlock: no content, reasoningContent, or toolCalls")
                    }
                } catch (e: Exception) {
                    println("[A2H-DEBUG] parseBlock exception: ${e.message}")
                    return null
                }
            }
        }
        return null
    }

    private fun executeTool(tc: ToolCallInfo): ToolResult {
        return try {
            val args = JsonParser.parseString(tc.arguments).asJsonObject
            val result = when (tc.name) {
                "readFile" -> {
                    val path = args.get("path")?.asString ?: ""
                    ideTools.readFile(path).fold(
                        onSuccess = { "Contents of $path:\n\n$it" },
                        onFailure = { "Error reading file: ${it.message}" }
                    )
                }
                "searchCode" -> {
                    val query = args.get("query")?.asString ?: ""
                    ideTools.searchCode(query)
                }
                "listProjectFiles" -> {
                    val dir = args.get("dir")?.asString ?: ""
                    ideTools.listProjectFiles(dir).fold(
                        onSuccess = { "Directory listing of $dir:\n\n$it" },
                        onFailure = { "Error listing directory: ${it.message}" }
                    )
                }
                "writeCode" -> {
                    val path = args.get("path")?.asString ?: ""
                    val content = args.get("content")?.asString ?: ""
                    ideTools.writeCode(path, content).fold(
                        onSuccess = { "File written successfully: $path" },
                        onFailure = { "Error writing file: ${it.message}" }
                    )
                }
                else -> "Unknown tool: ${tc.name}"
            }
            ToolResult(result)
        } catch (e: Exception) {
            ToolResult("Error executing tool ${tc.name}: ${e.message}")
        }
    }

    private fun makeSingleParam(name: String, type: String, desc: String): JsonObject {
        val params = JsonObject()
        params.addProperty("type", "object")
        val properties = JsonObject()
        val propObj = JsonObject()
        propObj.addProperty("type", type)
        propObj.addProperty("description", desc)
        properties.add(name, propObj)
        params.add("properties", properties)
        val required = JsonArray()
        required.add(name)
        params.add("required", required)
        return params
    }

    private fun makeMultiParams(params: Map<String, Pair<String, String>>): JsonObject {
        val json = JsonObject()
        json.addProperty("type", "object")
        val properties = JsonObject()
        val required = JsonArray()
        for ((name, pair) in params) {
            val (type, desc) = pair
            val propObj = JsonObject()
            propObj.addProperty("type", type)
            propObj.addProperty("description", desc)
            properties.add(name, propObj)
            required.add(name)
        }
        json.add("properties", properties)
        json.add("required", required)
        return json
    }
}
