package com.romaevt.wordcounter

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import kotlinx.coroutines.CoroutineScope

class WordCounterWidgetFactory : StatusBarEditorBasedWidgetFactory() {
    companion object {
        const val ID = "WordCounter"
    }

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Word Counter"

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = WordCounterWidget(scope)
}
