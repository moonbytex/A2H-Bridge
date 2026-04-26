package com.github.moonbytex.a2hbridge.api

import com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val content: Any?,
    val tool_calls: List<com.github.moonbytex.a2hbridge.util.MessageToolCall>? = null,
    val tool_call_id: String? = null
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
        fun assistant(content: String, toolCalls: List<com.github.moonbytex.a2hbridge.util.MessageToolCall>) =
            ChatMessage("assistant", content, toolCalls)
        fun tool(content: String, toolCallId: String) =
            ChatMessage("tool", content, tool_call_id = toolCallId)
    }
}

data class ToolCallDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

class OpenAIClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolCallDefinition> = emptyList()
    ): okhttp3.Response {
        val settings = A2HBridgeSettings.getInstance().state
        println("[A2H-DEBUG] OpenAIClient.streamChat: url=${settings.apiBaseUrl}, model=${settings.modelName}, key=${settings.apiKey.take(8)}...")
        require(settings.apiKey.isNotBlank()) { "API Key is not configured. Please set it in Settings -> Tools -> A2H-Bridge" }

        val body = buildRequestBody(messages, tools, settings.modelName)
        println("[A2H-DEBUG] Request body: $body")

        val request = Request.Builder()
            .url("${settings.apiBaseUrl}/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        return client.newCall(request).execute()
    }

    fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolCallDefinition> = emptyList()
    ): String {
        val settings = A2HBridgeSettings.getInstance().state
        require(settings.apiKey.isNotBlank()) { "API Key is not configured. Please set it in Settings -> Tools -> A2H-Bridge" }

        val body = buildRequestBody(messages, tools, settings.modelName, stream = false)

        val request = Request.Builder()
            .url("${settings.apiBaseUrl}/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "API error ${response.code}: ${response.body?.string()}" }

            val root = JsonParser.parseString(response.body?.string()).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val message = choices[0].asJsonObject.getAsJsonObject("message")
                if (message != null) {
                    val content = message.get("content")
                    if (content != null && !content.isJsonNull) {
                        return content.asString
                    }
                }
            }
            return "No response from API"
        }
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolCallDefinition>,
        modelName: String,
        stream: Boolean = true
    ): String {
        val json = JsonObject()
        json.addProperty("model", modelName)
        json.addProperty("stream", stream)

        val messagesArray = JsonArray()
        messages.forEach { msg ->
            val msgObj = JsonObject()
            msgObj.addProperty("role", msg.role)
            msgObj.addProperty("content", msg.content?.toString() ?: "")
            msg.tool_calls?.let { toolCalls ->
                val tcArray = JsonArray()
                toolCalls.forEach { tc ->
                    val tcObj = JsonObject()
                    tcObj.addProperty("id", tc.id)
                    tcObj.addProperty("type", tc.type)
                    val funcObj = JsonObject()
                    funcObj.addProperty("name", tc.function.name)
                    funcObj.addProperty("arguments", tc.function.arguments)
                    tcObj.add("function", funcObj)
                    tcArray.add(tcObj)
                }
                msgObj.add("tool_calls", tcArray)
            }
            msg.tool_call_id?.let { msgObj.addProperty("tool_call_id", it) }
            messagesArray.add(msgObj)
        }
        json.add("messages", messagesArray)

        if (tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            tools.forEach { tool ->
                val toolObj = JsonObject()
                toolObj.addProperty("type", tool.type)
                toolObj.add("function", gson.toJsonTree(tool.function).asJsonObject)
                toolsArray.add(toolObj)
            }
            json.add("tools", toolsArray)
        }

        return gson.toJson(json)
    }
}
