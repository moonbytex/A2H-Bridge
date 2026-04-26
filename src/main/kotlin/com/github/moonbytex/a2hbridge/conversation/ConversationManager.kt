package com.github.moonbytex.a2hbridge.conversation

import com.github.moonbytex.a2hbridge.agent.AgentType
import com.github.moonbytex.a2hbridge.api.ChatMessage
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ConversationManager(project: Project) {

    private val histories = mutableMapOf<AgentType, MutableList<ChatMessage>>()

    fun addMessage(agent: AgentType, message: ChatMessage) {
        getOrCreateHistory(agent).add(message)
    }

    fun getMessagesForAPI(agent: AgentType): List<ChatMessage> {
        return getOrCreateHistory(agent).toList()
    }

    fun clear(agent: AgentType) {
        histories[agent] = mutableListOf()
    }

    fun clearAll() {
        histories.clear()
    }

    fun appendToLastAssistant(agent: AgentType, content: String) {
        val history = getOrCreateHistory(agent)
        if (history.isNotEmpty() && history.last().role == "assistant") {
            val last = history.last()
            val lastContent = last.content?.toString() ?: ""
            history[history.size - 1] = ChatMessage.assistant(lastContent + content)
        } else {
            addMessage(agent, ChatMessage.assistant(content))
        }
    }

    fun appendToLastAssistantReasoning(agent: AgentType, content: String) {
        val history = getOrCreateHistory(agent)
        if (history.isNotEmpty() && history.last().role == "assistant") {
            val last = history.last()
            val lastContent = last.content?.toString() ?: ""
            history[history.size - 1] = ChatMessage.assistant(lastContent + "<think>$content</think>")
        } else {
            addMessage(agent, ChatMessage.assistant("<think>$content</think>"))
        }
    }

    private fun getOrCreateHistory(agent: AgentType): MutableList<ChatMessage> {
        return histories.getOrPut(agent) { mutableListOf() }
    }
}
