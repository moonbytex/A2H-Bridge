package com.github.moonbytex.a2hbridge.ui

import com.github.moonbytex.a2hbridge.agent.AgentExecutor
import com.github.moonbytex.a2hbridge.agent.AgentType
import com.github.moonbytex.a2hbridge.agent.ToolResult
import com.github.moonbytex.a2hbridge.conversation.ConversationManager
import com.github.moonbytex.a2hbridge.util.ToolCallInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class JsBridge(
    private val browser: JBCefBrowser,
    private val project: Project,
    private val conversationManager: ConversationManager
) {
    private val log = Logger.getInstance(JsBridge::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAgent = AgentType.UNDERSTANDING

    @Volatile
    private var confirmationResult: Pair<String, Boolean>? = null

    fun registerHandlers() {
        println("[A2H-DEBUG] registerHandlers called")
        log.info("registerHandlers called")

        // Create JBCefJSQuery for JS->Kotlin communication
        val query = JBCefJSQuery.create(browser)
        query.addHandler { message ->
            println("[A2H-DEBUG] JBCefJSQuery received: $message")
            log.info("JBCefJSQuery received: $message")
            try {
                val msg = com.google.gson.JsonParser.parseString(message).asJsonObject
                val type = msg.get("type")?.asString ?: return@addHandler null

                when (type) {
                    "sendMessage" -> {
                        val agentId = msg.get("agentId")?.asString ?: ""
                        val messageText = msg.get("message")?.asString ?: ""
                        handleSendMessage(agentId, messageText)
                    }
                    "confirmToolCall" -> {
                        val id = msg.get("id")?.asString ?: ""
                        val confirmed = msg.get("confirmed")?.asBoolean ?: false
                        handleConfirmToolCall(id, confirmed)
                    }
                    "selectAgent" -> {
                        val agentId = msg.get("agentId")?.asString ?: ""
                        handleSelectAgent(agentId)
                    }
                    "clearConversation" -> handleClearConversation()
                    "openFile" -> {
                        val filePath = msg.get("filePath")?.asString ?: ""
                        handleOpenFile(filePath)
                    }
                    "openSettings" -> handleOpenSettings()
                    "loadSettings" -> handleLoadSettings()
                    "saveSettings" -> {
                        val url = msg.get("url")?.asString ?: ""
                        val key = msg.get("key")?.asString ?: ""
                        val modelsStr = msg.get("models")?.asString ?: ""
                        val selectedModel = msg.get("selectedModel")?.asString ?: ""
                        log.info("saveSettings received: url=$url, modelsStr=$modelsStr, selectedModel=$selectedModel")
                        println("[A2H-DEBUG] saveSettings: url=$url, modelsStr=$modelsStr, selectedModel=$selectedModel")
                        val models = if (modelsStr.isNotEmpty()) {
                            com.google.gson.JsonParser.parseString(modelsStr).asJsonArray
                        } else {
                            com.google.gson.JsonArray()
                        }
                        handleSaveSettings(url, key, models, selectedModel)
                    }
                    "addModel" -> {
                        val newModel = msg.get("model")?.asString ?: ""
                        handleAddModel(newModel)
                    }
                }
            } catch (e: Exception) {
                log.error("Error parsing message from JS", e)
            }
            null // No response needed
        }

        // Add load handler to inject bridge on page load
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                println("[A2H-DEBUG] onLoadEnd called, httpStatusCode=$httpStatusCode, frame.isMain=${frame?.isMain}")
                log.info("onLoadEnd called, frame.isMain=${frame?.isMain}")
                if (frame?.isMain == true) {
                    println("[A2H-DEBUG] onLoadEnd: injecting bridge")
                    injectBridge(frame, query)
                    applyTheme(frame)
                }
            }
        }, browser.cefBrowser)
    }

    private fun applyTheme(frame: CefFrame) {
        val isDark = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel?.isDark == true
        val theme = if (isDark) "dark" else "light"
        frame.executeJavaScript("document.body.classList.add('theme-$theme');", null, 0)
    }

    private fun injectBridge(frame: CefFrame, query: JBCefJSQuery) {
        println("[A2H-DEBUG] injectBridge called")
        log.info("injectBridge called")

        val bridgeCode = """
            (function() {
                window.kotlinBridge = {
                    sendMessage: function(agentId, msg) {
                        ${query.inject("JSON.stringify({type:'sendMessage',agentId:agentId,message:msg})")}
                    },
                    confirmToolCall: function(id, confirmed) {
                        ${query.inject("JSON.stringify({type:'confirmToolCall',id:id,confirmed:confirmed})")}
                    },
                    selectAgent: function(agentId) {
                        ${query.inject("JSON.stringify({type:'selectAgent',agentId:agentId})")}
                    },
                    clearConversation: function() {
                        ${query.inject("JSON.stringify({type:'clearConversation'})")}
                    },
                    openFileInEditor: function(filePath) {
                        ${query.inject("JSON.stringify({type:'openFile',filePath:filePath})")}
                    },
                    openSettings: function() {
                        ${query.inject("JSON.stringify({type:'openSettings'})")}
                    },
                    loadSettings: function() {
                        ${query.inject("JSON.stringify({type:'loadSettings'})")}
                    },
                    saveSettings: function(url, key, models, selectedModel) {
                        ${query.inject("JSON.stringify({type:'saveSettings',url:url,key:key,models:models,selectedModel:selectedModel})")}
                    }
                };

                console.log('[kotlinBridge] Bridge injected successfully');

                if (window.onBridgeReady) {
                    window.onBridgeReady();
                }
            })();
        """.trimIndent()

        log.info("Injecting bridge code")
        frame.executeJavaScript(bridgeCode, null, 0)
    }

    fun streamChunk(content: String) {
        println("[A2H-DEBUG] streamChunk: length=${content.length}")
        val escaped = content
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        jsCall("window.onStreamChunk('$escaped')")
    }

    fun streamDone() {
        jsCall("window.onStreamDone()")
    }

    fun streamReasoning(content: String) {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        jsCall("window.onReasoningChunk('$escaped')")
    }

    fun showToolCallConfirmation(toolCall: ToolCallInfo) {
        val args = toolCall.arguments
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        val name = toolCall.name.replace("'", "\'")
        val id = toolCall.id.replace("'", "\\'")
        jsCall("window.onToolCallConfirmation('$id', '$name', '$args')")
    }

    private fun jsCall(code: String) {
        println("[A2H-DEBUG] jsCall: $code")
        log.info("jsCall: $code")
        browser.cefBrowser.executeJavaScript(code, null, 0)
    }

    private fun handleSendMessage(agentId: String, message: String) {
        println("[A2H-DEBUG] handleSendMessage: agentId=$agentId, message=$message")
        log.info("handleSendMessage: agentId=$agentId, message=$message")

        val agent = AgentType.entries.find { it.id == agentId } ?: currentAgent
        currentAgent = agent
        println("[A2H-DEBUG] Starting agent: $agent")
        log.info("Starting agent execution for agent: $agent")

        scope.launch(Dispatchers.IO) {
            try {
                val executor = AgentExecutor(
                    project = project,
                    agentType = agent,
                    conversationManager = conversationManager,
                    onChunk = { chunk ->
                        log.info("Received chunk length: ${chunk.length}")
                        streamChunk(chunk)
                    },
                    onReasoning = { reasoning ->
                        log.info("Received reasoning length: ${reasoning.length}")
                        streamReasoning(reasoning)
                    },
                    onToolCall = { tc ->
                        if (tc.name == "writeCode") {
                            showToolCallConfirmation(tc)
                            waitForConfirmation(tc.id)
                        } else {
                            ToolResult(content = "", confirmed = true)
                        }
                    },
                    onDone = {
                        log.info("Stream done")
                        streamDone()
                    }
                )

                println("[A2H-DEBUG] Calling executor.execute")
                log.info("Calling executor.execute")
                executor.execute(message).join()
                println("[A2H-DEBUG] Executor execution completed")
                log.info("Executor execution completed")
            } catch (e: Exception) {
                log.error("Error in handleSendMessage", e)
                println("[A2H-DEBUG] Error in handleSendMessage: ${e.message}")
                e.printStackTrace()
                streamChunk("\n\n[错误: ${e.message ?: "未知错误"}]")
                streamDone()
            }
        }
    }

    private suspend fun waitForConfirmation(toolCallId: String): ToolResult {
        confirmationResult = null
        while (confirmationResult == null || confirmationResult!!.first != toolCallId) {
            delay(100)
        }
        val confirmed = confirmationResult!!.second
        confirmationResult = null
        return ToolResult(
            content = if (confirmed) "User confirmed write operation" else "User denied write operation",
            confirmed = confirmed
        )
    }

    private fun handleConfirmToolCall(toolCallId: String, confirmed: Boolean) {
        log.info("handleConfirmToolCall: $toolCallId, $confirmed")
        confirmationResult = toolCallId to confirmed
    }

    private fun handleSelectAgent(agentId: String) {
        log.info("handleSelectAgent: $agentId")
        currentAgent = AgentType.entries.find { it.id == agentId } ?: currentAgent
    }

    private fun handleClearConversation() {
        log.info("handleClearConversation")
        conversationManager.clear(currentAgent)
        jsCall("window.onConversationCleared()")
    }

    private fun handleOpenFile(filePath: String) {
        log.info("handleOpenFile: $filePath")
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (virtualFile != null) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun handleOpenSettings() {
        println("[A2H-DEBUG] handleOpenSettings - calling showSettings() in JS")
        log.info("handleOpenSettings")
        browser.cefBrowser.executeJavaScript("showSettings();", null, 0)
    }

    private fun handleLoadSettings() {
        log.info("handleLoadSettings")
        val settings = com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings.getInstance()
        val state = settings.getState()
        val escapedUrl = state.apiBaseUrl.replace("\\", "\\\\").replace("'", "\\'")
        val escapedKey = state.apiKey.replace("\\", "\\\\").replace("'", "\\'")
        val selectedModel = state.modelName.replace("\\", "\\\\").replace("'", "\\'")
        // Send model list as raw JS array (not a string)
        val modelsJs = state.modelList.joinToString(", ") { "'${it.replace("\\", "\\\\").replace("'", "\\'")}'" }
        jsCall("window.onSettingsLoaded('$escapedUrl', '$escapedKey', [${modelsJs}], '$selectedModel')")
    }

    private fun handleSaveSettings(url: String, key: String, models: com.google.gson.JsonArray, selectedModel: String) {
        log.info("handleSaveSettings: url=$url, models=$models, selectedModel=$selectedModel")
        val settings = com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings.getInstance()
        val modelList = mutableListOf<String>()
        models.forEach { modelList.add(it.asString) }
        log.info("Parsed model list: $modelList")
        // Create a NEW State instance so persistence framework recognizes the change
        val newState = com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings.State(
            apiBaseUrl = url,
            apiKey = key,
            modelList = modelList,
            modelName = selectedModel
        )
        settings.loadState(newState)
    }

    private fun handleAddModel(modelName: String) {
        log.info("handleAddModel: $modelName")
        val settings = com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings.getInstance()
        val state = settings.getState()
        if (modelName.isNotBlank() && modelName !in state.modelList) {
            state.modelList.add(modelName)
            if (state.modelName.isBlank() || state.modelName !in state.modelList) {
                state.modelName = modelName
            }
            // Create a NEW State instance for persistence
            val newState = com.github.moonbytex.a2hbridge.settings.A2HBridgeSettings.State(
                apiBaseUrl = state.apiBaseUrl,
                apiKey = state.apiKey,
                modelList = state.modelList.toMutableList(),
                modelName = state.modelName
            )
            settings.loadState(newState)
        }
    }
}
