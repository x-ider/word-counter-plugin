package com.romaevt.wordcounter

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.ClickListener
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

open class WordCounterWidget(
    project: Project,
    private val scope: CoroutineScope,
    private val settings: WordCounterSettings = service(),
) : EditorBasedWidget(project), CustomStatusBarWidget, StatusBarWidget.Multiframe {
    private val panel by lazy {
        TextPanel().apply {
            isVisible = false
            isFocusable = true
            object : ClickListener() {
                override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                    if (!SwingUtilities.isLeftMouseButton(event)) return false
                    showPopup()
                    return true
                }
            }.installOn(this)
            for (key in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_SPACE)) {
                registerKeyboardAction({ showPopup() }, KeyStroke.getKeyStroke(key, 0), JComponent.WHEN_FOCUSED)
            }
        }
    }
    private val activeEditor = MutableStateFlow<Editor?>(null)
    private var updates: Job? = null
    private var popup: ListPopup? = null

    private data class Snapshot(val text: CharSequence, val stamp: Long, val selections: List<SourceRange>)
    private data class Cache(val stamp: Long, val parsed: ParsedDocument, val selections: List<SourceRange>, val counts: TextCounts)

    override fun ID(): String = WordCounterWidgetFactory.ID

    override fun getComponent(): JComponent = panel

    override fun copy(): StatusBarWidget = WordCounterWidget(project, scope, settings)

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        updates?.cancel()
        myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refreshEditor()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refreshEditor()
            override fun selectionChanged(event: FileEditorManagerEvent) = refreshEditor()
        })
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusListener = PropertyChangeListener { refreshEditor() }
        focusManager.addPropertyChangeListener("permanentFocusOwner", focusListener)
        Disposer.register(this) {
            focusManager.removePropertyChangeListener("permanentFocusOwner", focusListener)
        }
        updates = scope.launch(Dispatchers.EDT) {
            activeEditor.collectLatest { observeEditor(it) }
        }
        refreshEditor()
    }

    private fun refreshEditor() {
        // Let the IDE finish switching tabs/focus before resolving this frame's editor.
        SwingUtilities.invokeLater {
            if (!isDisposed) activeEditor.value = getEditor()?.takeUnless { it.isDisposed }
        }
    }

    private suspend fun observeEditor(editor: Editor?) {
        hide()
        if (editor == null || editor.isDisposed) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val format = formatFor(file) ?: return
        if (!file.isValid) return
        val document = editor.document
        val lifetime = Disposer.newDisposable("Word Counter active editor")
        Disposer.register(this, lifetime)
        val revision = AtomicLong()
        val lastEdit = AtomicLong()
        val requests = MutableStateFlow(0L)
        var cache: Cache? = null
        var displayedWhitespace = settings.includeWhitespace

        fun request(edited: Boolean = false) {
            if (edited) lastEdit.set(System.nanoTime())
            requests.value = revision.incrementAndGet()
        }

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { request(edited = true) }
        }, lifetime)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) { request() }
        }, lifetime)

        try {
            combine(requests, settings.preferences) { version, preferences -> version to preferences }
                .collectLatest { (version, preferences) ->
                    try {
                        if (preferences.includeWhitespace != displayedWhitespace) {
                            cache?.let { showCounts(it.counts, it.selections.isNotEmpty(), preferences.includeWhitespace) }
                            displayedWhitespace = preferences.includeWhitespace
                        }
                        // Selection events caused by typing must not bypass the typing pause.
                        if (cache?.stamp != document.modificationStamp && lastEdit.get() != 0L) {
                            val elapsedMs = (System.nanoTime() - lastEdit.get()) / 1_000_000
                            delay((preferences.updateDelayMs.toLong() - elapsedMs).coerceAtLeast(0))
                        }
                        val previous = cache
                        val result = withContext(Dispatchers.Default) {
                            val snapshot = readAction {
                                Snapshot(document.immutableCharSequence, document.modificationStamp, selections(editor))
                            }
                            // The parser and selection calculations never hold the IDE's read lock.
                            val parsed = previous?.takeIf { it.stamp == snapshot.stamp }?.parsed
                                ?: WordCounter.parse(snapshot.text, format)
                            val counts = if (previous?.stamp == snapshot.stamp && previous.selections == snapshot.selections) {
                                previous.counts
                            } else parsed.countSelections(snapshot.selections)
                            Cache(snapshot.stamp, parsed, snapshot.selections, counts)
                        }
                        if (editor.isDisposed || !file.isValid || requests.value != version ||
                            document.modificationStamp != result.stamp || selections(editor) != result.selections) return@collectLatest
                        cache = result
                        showCounts(result.counts, result.selections.isNotEmpty(), preferences.includeWhitespace)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cancelled: ProcessCanceledException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Logger.getInstance(WordCounterWidget::class.java).warn("Could not count the active document", error)
                        show("Count unavailable", "Word Counter could not count this document.")
                    }
                }
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    private fun selections(editor: Editor): List<SourceRange> = editor.caretModel.allCarets
        .filter { it.hasSelection() }
        .map { SourceRange(it.selectionStart, it.selectionEnd) }

    private fun showCounts(counts: TextCounts, selected: Boolean, includeWhitespace: Boolean) {
        val number = NumberFormat.getIntegerInstance()
        val words = "${number.format(counts.words)} ${if (counts.words == 1) "word" else "words"}"
        val characterCount = if (includeWhitespace) counts.characters else counts.charactersWithoutWhitespace
        val characters = "${number.format(characterCount)} ${if (characterCount == 1) "char" else "chars"}"
        show(
            "$words \u00B7 $characters",
            "<html>${if (selected) "Selection" else "Whole document"}<br>Words: ${number.format(counts.words)}" +
                "<br>Characters (including whitespace): ${number.format(counts.characters)}" +
                "<br>Characters (excluding whitespace): ${number.format(counts.charactersWithoutWhitespace)}</html>",
        )
    }

    private fun show(text: String, tooltip: String) {
        panel.text = text
        panel.toolTipText = tooltip
        panel.isVisible = true
        statusBar?.updateWidget(ID())
    }

    private fun hide() {
        popup?.cancel()
        panel.isVisible = false
        panel.text = null
        panel.toolTipText = null
        statusBar?.updateWidget(ID())
    }

    private fun showPopup() {
        if (!panel.isShowing || popup?.isVisible == true) return
        val menu = WordCounterPopup(settings, panel).create()
        popup = menu
        Disposer.register(this, menu)
        menu.show(RelativePoint(panel, Point(0, -menu.content.preferredSize.height)))
    }

    override fun dispose() {
        super<EditorBasedWidget>.dispose()
        updates?.cancel()
        updates = null
        activeEditor.value = null
        popup = null
    }

    private fun formatFor(file: VirtualFile): DocumentFormat? = when (file.extension?.lowercase()) {
        "txt", "text" -> DocumentFormat.PLAIN_TEXT
        "md", "markdown" -> DocumentFormat.MARKDOWN
        "mdx" -> DocumentFormat.MDX
        else -> null
    }
}
