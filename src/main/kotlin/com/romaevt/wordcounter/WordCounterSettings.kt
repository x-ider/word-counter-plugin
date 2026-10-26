package com.romaevt.wordcounter

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.APP)
@State(name = "WordCounterSettings", storages = [Storage("word-counter.xml")])
class WordCounterSettings : SerializablePersistentStateComponent<WordCounterSettings.Preferences>(Preferences()) {
    data class Preferences(
        // XML deserialization needs writable fields; runtime updates use copy-on-write below.
        @JvmField var updateDelayMs: Int = 250,
        @JvmField var includeWhitespace: Boolean = true,
    )

    private val changes = MutableStateFlow(state)
    val preferences = changes.asStateFlow()

    var updateDelayMs: Int
        get() = state.updateDelayMs
        set(value) {
            require(value > 0) { "Update delay must be a positive number of milliseconds" }
            updateState { it.copy(updateDelayMs = value) }
            changes.value = state
        }

    var includeWhitespace: Boolean
        get() = state.includeWhitespace
        set(value) {
            updateState { it.copy(includeWhitespace = value) }
            changes.value = state
        }

    override fun loadState(state: Preferences) {
        super.loadState(if (state.updateDelayMs > 0) state else state.copy(updateDelayMs = 250))
        changes.value = this.state
    }

    companion object {
        internal fun parseDelay(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it > 0 }
    }
}
