package com.github.moonbytex.a2hbridge.ui

import com.github.moonbytex.a2hbridge.conversation.ConversationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import java.io.BufferedReader

class A2HToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val conversationManager = project.getService(ConversationManager::class.java)
        val jsBridge = JsBridge(browser, project, conversationManager)

        jsBridge.registerHandlers()

        val htmlContent = loadResourceAsString("html/chat.html")
        if (htmlContent != null) {
            val tempFile = java.io.File.createTempFile("a2hbridge_chat_", ".html").apply {
                writeText(htmlContent, Charsets.UTF_8)
                deleteOnExit()
            }
            val path = tempFile.absolutePath.replace("\\", "/")
            browser.loadURL("file:///$path")
        }

        val content = ContentFactory.getInstance().createContent(browser.component, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun loadResourceAsString(path: String): String? {
        return try {
            A2HToolWindowFactory::class.java.classLoader.getResourceAsStream(path)?.use { stream ->
                BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}
