package com.github.moonbytex.a2hbridge.util

data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
) {
    fun toMessageToolCall(): MessageToolCall {
        return MessageToolCall(id, "function", MessageFunctionCall(name, arguments))
    }
}

data class MessageToolCall(
    val id: String,
    val type: String = "function",
    val function: MessageFunctionCall
)

data class MessageFunctionCall(
    val name: String,
    val arguments: String
)
