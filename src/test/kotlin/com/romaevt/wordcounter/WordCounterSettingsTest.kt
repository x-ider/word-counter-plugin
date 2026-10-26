package com.romaevt.wordcounter

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.JPanel

class WordCounterSettingsTest : BasePlatformTestCase() {
    fun testApplicationServiceAndDefaults() {
        assertSame(service<WordCounterSettings>(), service<WordCounterSettings>())
        val settings = WordCounterSettings()
        assertEquals(250, settings.updateDelayMs)
        assertTrue(settings.includeWhitespace)
    }

    fun testPreferencesRoundTripThroughXmlAndNotifyObservers() {
        val settings = WordCounterSettings()
        settings.updateDelayMs = 750
        settings.includeWhitespace = false
        val xml = XmlSerializer.serialize(settings.state)
        val restored = WordCounterSettings()
        restored.loadState(XmlSerializer.deserialize(xml, WordCounterSettings.Preferences::class.java))
        assertEquals(750, restored.updateDelayMs)
        assertFalse(restored.includeWhitespace)
        assertEquals(restored.state, restored.preferences.value)
    }

    fun testInvalidStoredDelayFallsBackWithoutResettingWhitespace() {
        val settings = WordCounterSettings()
        settings.loadState(WordCounterSettings.Preferences(-10, false))
        assertEquals(250, settings.updateDelayMs)
        assertFalse(settings.includeWhitespace)
    }

    fun testDelayValidation() {
        for (value in listOf("", "0", "-1", "1.5", "abc", "2147483648")) {
            assertNull(WordCounterSettings.parseDelay(value))
        }
        assertEquals(1, WordCounterSettings.parseDelay("1"))
        assertEquals(500, WordCounterSettings.parseDelay(" 500 "))
        assertEquals(Int.MAX_VALUE, WordCounterSettings.parseDelay(Int.MAX_VALUE.toString()))
    }

    fun testPopupContainsDelayActionAndWhitespaceCheckbox() {
        val settings = WordCounterSettings()
        val actions = WordCounterPopup(settings, JPanel()).actions().getChildren(null)
        assertEquals(2, actions.size)
        assertEquals("Update delay: 250 ms...", actions[0].templatePresentation.text)
        assertEquals("Include whitespace", actions[1].templatePresentation.text)
        val toggle = actions[1] as ToggleAction
        val event = AnActionEvent.createEvent(toggle, DataContext.EMPTY_CONTEXT, toggle.templatePresentation.clone(), "WordCounterTest", ActionUiKind.POPUP, null)
        assertTrue(toggle.isSelected(event))
        toggle.setSelected(event, false)
        assertFalse(settings.includeWhitespace)
    }

    fun testDelayPromptAppliesInputAndCancelLeavesItUnchanged() {
        val settings = WordCounterSettings()
        val action = WordCounterPopup(settings, JPanel()).actions().getChildren(null)[0]
        val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, action.templatePresentation.clone(), "WordCounterTest", ActionUiKind.POPUP, null)
        val previous = TestDialogManager.setTestInputDialog(TestInputDialog { "750" })
        try {
            action.actionPerformed(event)
            assertEquals(750, settings.updateDelayMs)
            TestDialogManager.setTestInputDialog(TestInputDialog { null })
            action.actionPerformed(event)
            assertEquals(750, settings.updateDelayMs)
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }
    }

    fun testNativePopupCanBeCreatedAndDisposed() {
        val popup = WordCounterPopup(WordCounterSettings(), JPanel()).create()
        try {
            assertEquals(2, popup.listStep.values.size)
            assertTrue(popup.content.preferredSize.width > 0)
            val list = (popup as ListPopupImpl).list
            assertEquals(2, list.model.size)
            assertTrue(list.preferredSize.width > 0 && list.preferredSize.height > 0)
        } finally {
            Disposer.dispose(popup)
        }
    }
}
