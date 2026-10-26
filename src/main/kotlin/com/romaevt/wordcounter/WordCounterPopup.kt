package com.romaevt.wordcounter

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.TextRange
import javax.swing.JComponent

internal class WordCounterPopup(private val settings: WordCounterSettings, private val parent: JComponent) {
    fun create(): ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
        "Word Counter", actions(), DataManager.getInstance().getDataContext(parent),
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
    )

    internal fun actions(): DefaultActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Update delay: ${settings.updateDelayMs} ms...") {
            override fun actionPerformed(event: AnActionEvent) {
                val initial = settings.updateDelayMs.toString()
                val answer = Messages.showInputDialog(
                    parent, "Update delay (ms):", "Word Counter", null, initial,
                    object : InputValidatorEx {
                        override fun checkInput(input: String): Boolean = WordCounterSettings.parseDelay(input) != null
                        override fun canClose(input: String): Boolean = checkInput(input)
                        override fun getErrorText(input: String): String? =
                            if (WordCounterSettings.parseDelay(input) == null) "Enter a positive whole number of milliseconds." else null
                    },
                    TextRange(0, initial.length), null,
                ) ?: return
                WordCounterSettings.parseDelay(answer)?.let { settings.updateDelayMs = it }
            }
        },
        object : DumbAwareToggleAction("Include whitespace") {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent): Boolean = settings.includeWhitespace
            override fun setSelected(event: AnActionEvent, state: Boolean) { settings.includeWhitespace = state }
        },
    )
}
