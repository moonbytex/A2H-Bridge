package com.github.moonbytex.a2hbridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class A2HBridgeSettingsConfigurable : Configurable {

    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelNameField = JBTextField()

    override fun getDisplayName() = "A2H-Bridge"

    override fun createComponent(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Base URL:"), baseUrlField, 1, false)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model Name:"), modelNameField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = A2HBridgeSettings.getInstance().state
        return baseUrlField.text != settings.apiBaseUrl ||
                String(apiKeyField.password) != settings.apiKey ||
                modelNameField.text != settings.modelName
    }

    override fun apply() {
        val newState = A2HBridgeSettings.State(
            apiBaseUrl = baseUrlField.text,
            apiKey = String(apiKeyField.password),
            modelName = modelNameField.text
        )
        A2HBridgeSettings.getInstance().loadState(newState)
    }

    override fun reset() {
        val settings = A2HBridgeSettings.getInstance().state
        baseUrlField.text = settings.apiBaseUrl
        apiKeyField.text = settings.apiKey
        modelNameField.text = settings.modelName
    }
}
