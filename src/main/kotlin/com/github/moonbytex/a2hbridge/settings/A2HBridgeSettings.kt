package com.github.moonbytex.a2hbridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "A2HBridgeSettings",
    storages = [Storage("a2hbridge.xml")]
)
class A2HBridgeSettings : PersistentStateComponent<A2HBridgeSettings.State> {

    data class State(
        var apiBaseUrl: String = "https://coding.dashscope.aliyuncs.com/v1",
        var apiKey: String = "sk-sp-39540c68e5f04e30a8ed5f713172acc7",
        var modelList: MutableList<String> = mutableListOf("glm-5"),
        var modelName: String = "glm-5"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): A2HBridgeSettings =
            ApplicationManager.getApplication().getService(A2HBridgeSettings::class.java)
    }
}
