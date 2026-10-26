package com.romaevt.wordcounter

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.LeakHunter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import javax.imageio.ImageIO
import javax.swing.JPanel

class WordCounterWidgetTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var widget: WordCounterWidget
    private lateinit var settings: WordCounterSettings
    private val panel: TextPanel get() = widget.component as TextPanel

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = WordCounterSettings()
        widget = WordCounterWidget(project, scope, settings)
        Disposer.register(testRootDisposable, widget)
        widget.install(statusBar())
    }

    override fun tearDown() {
        try {
            Disposer.dispose(widget)
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testFactoryRegistrationDefaultsAndPlacement() {
        val factories = StatusBarWidgetFactory.EP_NAME.extensionList
        val factory = factories.single { it.id == WordCounterWidgetFactory.ID }
        assertTrue(factory is WordCounterWidgetFactory)
        assertEquals("Word Counter", factory.displayName)
        assertTrue(factory.isEnabledByDefault)
        val ids = factories.map { it.id }
        assertTrue("The host should expose its line/column widget", ids.contains("Position"))
        assertTrue(ids.indexOf(WordCounterWidgetFactory.ID) < ids.indexOf("Position"))
        val created = factory.createWidget(project, scope)
        try {
            assertEquals(factory.id, created.ID())
        } finally {
            factory.disposeWidget(created)
        }
    }

    fun testDocumentTotalsAndTooltip() {
        activate("article.md", "**Hello** [world](https://example.com)")
        awaitText("2 words \u00B7 11 chars")
        assertEquals(
            "<html>Whole document<br>Words: 2<br>Characters (including whitespace): 11" +
                "<br>Characters (excluding whitespace): 10</html>",
            panel.toolTipText,
        )
        assertEquals(panel.text, panel.accessibleContext.accessibleName)
    }

    fun testLocaleFormattedCountsRenderAtThePreferredSize() {
        activate("article.md", "word ".repeat(1_000))
        val number = NumberFormat.getIntegerInstance()
        awaitText("${number.format(1_000)} words \u00B7 ${number.format(4_999)} chars")
        val size = panel.preferredSize
        assertTrue(size.width > 0 && size.height > 0)
        panel.size = size
        val image = BufferedImage(size.width + 16, size.height + 16, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        val background = UIUtil.getPanelBackground()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.translate(8, 8)
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertTrue("The component should paint its text", (8 until image.width - 8).any { x ->
            (8 until image.height - 8).any { y -> image.getRGB(x, y) != background.rgb }
        })
        val output = Path.of("build/reports/widget-preview.png")
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())
    }

    fun testMdxUsesMdxRules() {
        activate("article.MDX", "---\ntitle: Hidden\n---\nimport Box from './Box';\n\n<Box title=\"Hidden\">Hello {value}world</Box>")
        awaitText("2 words \u00B7 11 chars")
    }

    fun testMarkdownExtensionAndLiteralBraces() {
        activate("article.markdown", "{hello} world")
        awaitText("2 words \u00B7 13 chars")
    }

    fun testPlainTextUsesLiteralCountingAndSwitchesBackToMarkdown() {
        activate("notes.TXT", "**Hello**")
        awaitText("1 word \u00B7 9 chars")
        activate("article.md", "**Hello**")
        awaitText("1 word \u00B7 5 chars")
    }

    fun testTextExtensionDoesNotStripFrontmatter() {
        activate("notes.text", "---\ntitle: Hi\n---")
        awaitText("2 words \u00B7 17 chars")
        assertTrue(panel.toolTipText?.contains("Characters (excluding whitespace): 14") == true)
    }

    fun testWhitespaceOnlyPlainTextStaysVisible() {
        activate("notes.txt", "\n \t")
        awaitText("0 words \u00B7 3 chars")
        assertTrue(panel.toolTipText?.contains("Characters (excluding whitespace): 0") == true)
    }

    fun testEmptyDocumentAndSingularLabels() {
        activate("empty.md", "")
        awaitText("0 words \u00B7 0 chars")
        activate("letter.md", "I")
        awaitText("1 word \u00B7 1 char")
    }

    fun testUnsupportedFilesAndNoEditorHideTheWidget() {
        activate("article.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        activate("code.kt", "val hidden = 42")
        awaitHidden()
        assertNull(panel.toolTipText)
        activate("again.md", "Visible")
        awaitText("1 word \u00B7 7 chars")
        closeAllFiles()
        awaitHidden()
    }

    fun testReactivationCountsUnsavedDocumentText() {
        val editor = activate("article.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        activate("code.kt", "val ignored = 42")
        awaitHidden()
        WriteCommandAction.runWriteCommandAction(project) { editor.editor.document.setText("Hello world") }
        FileEditorManager.getInstance(project).openFile(editor.file!!, true)
        awaitText("2 words \u00B7 11 chars")
    }

    fun testSelectionKeepsMarkdownContextAndClearingRestoresDocumentCounts() {
        val editor = activate("article.md", "Hello [world](https://example.com)")
        awaitText("2 words \u00B7 11 chars")
        val source = editor.editor.document.text
        val url = source.indexOf("https")
        editor.editor.selectionModel.setSelection(url, source.length - 1)
        awaitText("0 words \u00B7 0 chars")
        assertTrue(panel.toolTipText?.startsWith("<html>Selection") == true)
        editor.editor.selectionModel.setSelection(0, 5)
        awaitText("1 word \u00B7 5 chars")
        editor.editor.selectionModel.removeSelection()
        awaitText("2 words \u00B7 11 chars")
    }

    fun testMultipleCaretsCountTheirSelectionsTogether() {
        val editor = activate("notes.txt", "one middle two").editor
        awaitText("3 words \u00B7 14 chars")
        editor.caretModel.primaryCaret.setSelection(0, 3)
        val second = editor.caretModel.addCaret(editor.logicalToVisualPosition(LogicalPosition(0, 11)))!!
        second.setSelection(11, 14)
        awaitText("2 words \u00B7 6 chars")
    }

    fun testWhitespacePreferenceImmediatelyUpdatesCounts() {
        activate("notes.txt", "Hello world")
        awaitText("2 words \u00B7 11 chars")
        settings.includeWhitespace = false
        awaitText("2 words \u00B7 10 chars")
        assertTrue(panel.toolTipText?.contains("Characters (including whitespace): 11") == true)
        settings.includeWhitespace = true
        awaitText("2 words \u00B7 11 chars")
    }

    fun testWhitespacePreferenceUpdatesWhileTypingRefreshIsPending() {
        settings.updateDelayMs = 60_000
        val editor = activate("notes.txt", "Hello world")
        awaitText("2 words \u00B7 11 chars")
        WriteCommandAction.runWriteCommandAction(project) { editor.editor.document.setText("Pending content") }
        settings.includeWhitespace = false
        awaitText("2 words \u00B7 10 chars")
        settings.updateDelayMs = 1
        awaitText("2 words \u00B7 14 chars")
    }

    fun testSelectionEventsDoNotBypassTypingDelayAndChangingDelayReschedules() {
        settings.updateDelayMs = 60_000
        val editor = activate("notes.txt", "Old")
        awaitText("1 word \u00B7 3 chars")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.editor.document.setText("First edit")
            editor.editor.document.setText("Latest edit")
            editor.editor.selectionModel.setSelection(0, 6)
        }
        pumpFor(100)
        assertEquals("1 word \u00B7 3 chars", panel.text)
        settings.updateDelayMs = 1
        awaitText("1 word \u00B7 6 chars")
        editor.editor.selectionModel.removeSelection()
        awaitText("2 words \u00B7 11 chars")
    }

    fun testSwitchingFilesDoesNotWaitForPendingTypingDelay() {
        settings.updateDelayMs = 60_000
        val old = activate("old.md", "Old")
        awaitText("1 word \u00B7 3 chars")
        WriteCommandAction.runWriteCommandAction(project) { old.editor.document.setText("Pending old content") }
        activate("current.md", "Current")
        awaitText("1 word \u00B7 7 chars")
        settings.updateDelayMs = 1
        pumpFor(100)
        assertEquals("1 word \u00B7 7 chars", panel.text)
    }

    fun testDisposalWhileAnUpdateIsPendingDoesNotUpdateThePanel() {
        settings.updateDelayMs = 60_000
        val editor = activate("notes.txt", "Old")
        awaitText("1 word \u00B7 3 chars")
        WriteCommandAction.runWriteCommandAction(project) { editor.editor.document.setText("Pending content") }
        Disposer.dispose(widget)
        settings.updateDelayMs = 1
        pumpFor(100)
        assertEquals("1 word \u00B7 3 chars", panel.text)
    }

    fun testRapidSwitchingEndsWithTheCurrentDocument() {
        activate("long.md", "Many **words**.\n\n".repeat(5_000))
        activate("current.md", "Current")
        awaitText("1 word \u00B7 7 chars")
    }

    fun testCopiesHaveIndependentFrameStateAndDisposal() {
        val child = widget.copy() as WordCounterWidget
        Disposer.register(testRootDisposable, child)
        child.install(statusBar())
        val editor = activate("article.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        val childPanel = child.component as TextPanel
        await("The copied widget should count independently") { childPanel.text == panel.text }
        Disposer.dispose(widget)
        await("The other frame should still work") { childPanel.isVisible && childPanel.text == "1 word \u00B7 5 chars" }
        settings.includeWhitespace = false
        WriteCommandAction.runWriteCommandAction(project) { editor.editor.document.setText("Hello world") }
        await("The other frame should use the shared preference") { childPanel.text == "2 words \u00B7 10 chars" }
    }

    fun testEditorLookupIsPerWidgetNotTheProjectSelection() {
        val first = activate("first.txt", "One").editor
        val second = activate("second.txt", "Two words").editor
        val detached = object : WordCounterWidget(project, scope, settings) {
            var frameEditor: Editor? = first
            override fun getEditor(): Editor? = frameEditor
        }
        Disposer.register(testRootDisposable, detached)
        detached.install(statusBar())
        val detachedPanel = detached.component as TextPanel
        awaitText("2 words \u00B7 9 chars")
        await("The detached widget should resolve its own editor") { detachedPanel.text == "1 word \u00B7 3 chars" }

        // A project-wide event must refresh each widget through its own editor lookup.
        detached.frameEditor = second
        publishSelectionChanged()
        await("The detached widget should follow its own tab switch") { detachedPanel.text == "2 words \u00B7 9 chars" }
        detached.frameEditor = null
        publishSelectionChanged()
        await("Closing the detached editor should hide only its widget") { !detachedPanel.isVisible }
        assertEquals("2 words \u00B7 9 chars", panel.text)
    }

    fun testClosingSelectedFileRefreshesTheWidget() {
        activate("first.txt", "First")
        val second = activate("second.txt", "Second file")
        awaitText("2 words \u00B7 11 chars")
        FileEditorManager.getInstance(project).closeFile(second.file!!)
        await("The closed editor must no longer be counted") { panel.text != "2 words \u00B7 11 chars" }
        closeAllFiles()
        awaitHidden()
    }

    fun testFocusChangeRefreshesTheFrameEditorWithoutAFileEvent() {
        val first = activate("first.txt", "One").editor
        val second = activate("second.txt", "Two words").editor
        val originalManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusManager = object : DefaultKeyboardFocusManager() {
            fun changeFocus() = firePropertyChange("permanentFocusOwner", null, JPanel())
        }
        val frame = object : WordCounterWidget(project, scope, settings) {
            var frameEditor: Editor? = first
            override fun getEditor(): Editor? = frameEditor
        }
        Disposer.register(testRootDisposable, frame)
        KeyboardFocusManager.setCurrentKeyboardFocusManager(focusManager)
        try {
            frame.install(statusBar())
            val framePanel = frame.component as TextPanel
            await("The frame should initially count its own editor") { framePanel.text == "1 word \u00B7 3 chars" }
            frame.frameEditor = second
            focusManager.changeFocus()
            await("Focus alone should refresh the editor") { framePanel.text == "2 words \u00B7 9 chars" }
        } finally {
            Disposer.dispose(frame)
            KeyboardFocusManager.setCurrentKeyboardFocusManager(originalManager)
        }
    }

    fun testQueuedEditorRefreshAfterDisposalIsIgnored() {
        activate("article.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        publishSelectionChanged()
        Disposer.dispose(widget)
        pumpFor(100)
        await("Disposal should cancel all widget work") { scope.coroutineContext[Job]!!.children.none() }
    }

    fun testMarkdownEditsUndoAndRedoRefreshWithoutSwitchingTabs() {
        settings.updateDelayMs = 1
        val editor = activate("undo.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        WriteCommandAction.runWriteCommandAction(project) { editor.editor.document.setText("Hello **world**") }
        awaitText("2 words \u00B7 11 chars")
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        awaitText("1 word \u00B7 5 chars")
        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        awaitText("2 words \u00B7 11 chars")
    }

    fun testBurstOfEditsDeliversOnlyTheLatestTotalsAfterThePause() {
        settings.updateDelayMs = 60_000
        val editor = activate("burst.txt", "Hello").editor
        awaitText("1 word \u00B7 5 chars")
        repeat(100) {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(editor.document.textLength, " word") }
        }
        pumpFor(100)
        assertEquals("1 word \u00B7 5 chars", panel.text)
        settings.updateDelayMs = 1
        awaitText("101 words \u00B7 505 chars")
    }

    fun testColumnSelectionCountsEachSelectedLine() {
        val editor = activate("columns.txt", "one extra\ntwo extra").editor
        awaitText("4 words \u00B7 19 chars")
        editor.selectionModel.setBlockSelection(LogicalPosition(0, 0), LogicalPosition(1, 3))
        awaitText("2 words \u00B7 6 chars")
    }

    fun testLeavingTheEditorReleasesItsExtractedDocument() {
        activate("large.md", "Hello **world**.\n\n".repeat(5_000))
        await("The large document should be counted") { panel.isVisible }
        closeAllFiles()
        awaitHidden()
        pumpFor(100)
        LeakHunter.checkLeak(widget, ParsedDocument::class.java)
    }

    fun testDisposalReleasesItsExtractedDocumentAndCoroutineChildren() {
        activate("large.md", "Hello **world**.\n\n".repeat(5_000))
        await("The large document should be counted") { panel.isVisible }
        Disposer.dispose(widget)
        await("All widget coroutines should finish") { scope.coroutineContext[Job]!!.children.none() }
        LeakHunter.checkLeak(widget, ParsedDocument::class.java)
    }

    fun testRepeatedEnableAndDisableDoesNotAccumulateSubscriptions() {
        activate("article.md", "Hello")
        awaitText("1 word \u00B7 5 chars")
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listenerCount = focusManager.getPropertyChangeListeners("permanentFocusOwner").size
        repeat(10) {
            val another = widget.copy() as WordCounterWidget
            Disposer.register(testRootDisposable, another)
            another.install(statusBar())
            await("The additional widget should count") { another.component.isVisible }
            Disposer.dispose(another)
            await("Only the original widget should remain active") { scope.coroutineContext[Job]!!.children.count() == 1 }
            assertEquals(listenerCount, focusManager.getPropertyChangeListeners("permanentFocusOwner").size)
        }
        assertEquals("1 word \u00B7 5 chars", panel.text)
    }

    private fun activate(name: String, text: String): TextEditor {
        val file = myFixture.addFileToProject(name, text).virtualFile
        myFixture.openFileInEditor(file)
        return TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
    }

    private fun closeAllFiles() {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.forEach { manager.closeFile(it) }
    }

    private fun publishSelectionChanged() {
        val manager = FileEditorManager.getInstance(project)
        project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
            .selectionChanged(FileEditorManagerEvent(manager, null, null))
    }

    private fun awaitText(text: String) = await("Expected widget text: $text") { panel.isVisible && panel.text == text }

    private fun awaitHidden() = await("The widget should be hidden") { !panel.isVisible && panel.text == null }

    private fun await(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
    }

    private fun pumpFor(milliseconds: Long) {
        val end = System.nanoTime() + milliseconds * 1_000_000
        await("Dispatch events during the typing pause") { System.nanoTime() >= end }
    }

    // Editor tracking uses the real file manager; only the status-bar UI is replaced.
    private fun statusBar(): StatusBar = Proxy.newProxyInstance(
        StatusBar::class.java.classLoader, arrayOf(StatusBar::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getProject" -> project
            "updateWidget" -> null
            else -> error("Unexpected status bar call: ${method.name}")
        }
    } as StatusBar
}
