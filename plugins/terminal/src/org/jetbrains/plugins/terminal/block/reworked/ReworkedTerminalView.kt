// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.block.reworked

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.util.coroutines.childScope
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.asDisposable
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalUtil
import org.jetbrains.plugins.terminal.block.TerminalContentView
import org.jetbrains.plugins.terminal.block.output.NEW_TERMINAL_OUTPUT_CAPACITY_KB
import org.jetbrains.plugins.terminal.block.reworked.session.TerminalResizeEvent
import org.jetbrains.plugins.terminal.block.reworked.session.TerminalSession
import org.jetbrains.plugins.terminal.block.reworked.session.TerminalWriteBytesEvent
import org.jetbrains.plugins.terminal.block.reworked.session.startTerminalSession
import org.jetbrains.plugins.terminal.block.ui.TerminalUi.useTerminalDefaultBackground
import org.jetbrains.plugins.terminal.block.ui.TerminalUiUtils
import org.jetbrains.plugins.terminal.block.ui.getCharSize
import org.jetbrains.plugins.terminal.block.ui.stickScrollBarToBottom
import org.jetbrains.plugins.terminal.util.terminalProjectScope
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.JScrollPane

internal class ReworkedTerminalView(
  private val project: Project,
  private val settings: JBTerminalSystemSettingsProviderBase,
) : TerminalContentView {
  private val coroutineScope = terminalProjectScope(project).childScope("ReworkedTerminalView")
  private val terminalSessionFuture = CompletableFuture<TerminalSession>()

  private val terminationListeners: MutableList<Runnable> = CopyOnWriteArrayList()

  private val sessionModel: TerminalSessionModel
  private val encodingManager: TerminalKeyEncodingManager
  private val controller: TerminalSessionController

  private val outputModel: TerminalOutputModel
  private val alternateBufferModel: TerminalOutputModel

  private val terminalPanel: TerminalPanel

  override val component: JComponent
    get() = terminalPanel
  override val preferredFocusableComponent: JComponent
    get() = terminalPanel.preferredFocusableComponent

  init {
    Disposer.register(this) {
      // Complete to avoid memory leaks with hanging callbacks. If already completed, nothing will change.
      terminalSessionFuture.complete(null)

      coroutineScope.cancel()
    }

    coroutineScope.coroutineContext.job.invokeOnCompletion {
      for (listener in terminationListeners) {
        try {
          listener.run()
        }
        catch (t: Throwable) {
          thisLogger().error("Unhandled exception in termination listener", t)
        }
      }
    }

    sessionModel = TerminalSessionModelImpl(settings)
    encodingManager = TerminalKeyEncodingManager(sessionModel, coroutineScope.childScope("TerminalKeyEncodingManager"))

    outputModel = createOutputModel(
      editor = createOutputEditor(settings, parentDisposable = this),
      maxOutputLength = AdvancedSettings.getInt(NEW_TERMINAL_OUTPUT_CAPACITY_KB).coerceIn(1, 10 * 1024) * 1024,
      settings,
      sessionModel,
      encodingManager,
      terminalSessionFuture,
      coroutineScope.childScope("TerminalOutputModel")
    )

    alternateBufferModel = createOutputModel(
      editor = createAlternateBufferEditor(settings, parentDisposable = this),
      maxOutputLength = 0,
      settings,
      sessionModel,
      encodingManager,
      terminalSessionFuture,
      coroutineScope.childScope("TerminalAlternateBufferModel")
    )

    controller = TerminalSessionController(sessionModel, outputModel, alternateBufferModel, settings, coroutineScope.childScope("TerminalSessionController"))

    terminalPanel = TerminalPanel(initialContent = outputModel.editor)

    listenPanelSizeChanges()
    listenAlternateBufferSwitch()
  }

  override fun connectToTty(ttyConnector: TtyConnector, initialTermSize: TermSize) {
    val session = startTerminalSession(ttyConnector, initialTermSize, settings, coroutineScope.childScope("TerminalSession"))
    terminalSessionFuture.complete(session)

    controller.handleEvents(session.outputChannel)
  }

  override fun addTerminationCallback(onTerminated: Runnable, parentDisposable: Disposable) {
    TerminalUtil.addItem(terminationListeners, onTerminated, parentDisposable)
  }

  override fun sendCommandToExecute(shellCommand: String) {
    terminalSessionFuture.thenAccept {
      val newLineBytes = encodingManager.getCode(KeyEvent.VK_ENTER, 0)!!
      // TODO: should we always use UTF8?
      val bytes = shellCommand.toByteArray(Charsets.UTF_8) + newLineBytes
      it?.inputChannel?.trySend(TerminalWriteBytesEvent(bytes))
    }
  }

  override fun getTerminalSize(): TermSize? {
    val model = getCurOutputModel()
    val contentSize = model.editor.scrollingModel.visibleArea.size
    val charSize = model.editor.getCharSize()

    return if (contentSize.width > 0 && contentSize.height > 0) {
      TerminalUiUtils.calculateTerminalSize(contentSize, charSize)
    }
    else null
  }

  override fun getTerminalSizeInitializedFuture(): CompletableFuture<*> {
    return TerminalUiUtils.getComponentSizeInitializedFuture(component)
  }

  override fun isFocused(): Boolean {
    return component.hasFocus()
  }

  private fun listenPanelSizeChanges() {
    component.addComponentListener(object : ComponentAdapter() {
      override fun componentResized(e: ComponentEvent) {
        val inputChannel = terminalSessionFuture.getNow(null)?.inputChannel ?: return
        val newSize = getTerminalSize() ?: return
        inputChannel.trySend(TerminalResizeEvent(newSize))
      }
    })
  }

  private fun listenAlternateBufferSwitch() {
    coroutineScope.launch(Dispatchers.EDT + CoroutineName("Alternate buffer switch listener")) {
      var isAlternateScreenBuffer = false
      sessionModel.terminalState.collect { state ->
        if (state.isAlternateScreenBuffer != isAlternateScreenBuffer) {
          isAlternateScreenBuffer = state.isAlternateScreenBuffer

          val model = if (state.isAlternateScreenBuffer) alternateBufferModel else outputModel
          terminalPanel.setTerminalContent(model.editor)
          IdeFocusManager.getInstance(project).requestFocus(terminalPanel.preferredFocusableComponent, true)
        }
      }
    }
  }

  private fun getCurOutputModel(): TerminalOutputModel {
    return if (sessionModel.terminalState.value.isAlternateScreenBuffer) alternateBufferModel else outputModel
  }

  private fun createOutputModel(
    editor: EditorEx,
    maxOutputLength: Int,
    settings: JBTerminalSystemSettingsProviderBase,
    sessionModel: TerminalSessionModel,
    encodingManager: TerminalKeyEncodingManager,
    terminalSessionFuture: CompletableFuture<TerminalSession>,
    coroutineScope: CoroutineScope,
  ): TerminalOutputModel {
    val model = TerminalOutputModelImpl(editor, maxOutputLength)

    TerminalCursorPainter.install(model, coroutineScope.childScope("TerminalCursorPainter"))

    val eventsHandler = TerminalEventsHandlerImpl(sessionModel, model, encodingManager, terminalSessionFuture, settings)
    val parentDisposable = coroutineScope.asDisposable()
    setupKeyEventDispatcher(model.editor, eventsHandler, parentDisposable)
    setupMouseListener(model.editor, sessionModel, settings, eventsHandler, parentDisposable)

    return model
  }

  private fun createOutputEditor(settings: JBTerminalSystemSettingsProviderBase, parentDisposable: Disposable): EditorEx {
    val document = EditorFactory.getInstance().createDocument("")
    val editor = TerminalUiUtils.createOutputEditor(document, project, settings)
    editor.settings.isUseSoftWraps = true
    editor.useTerminalDefaultBackground(parentDisposable = this)
    stickScrollBarToBottom(editor.scrollPane.verticalScrollBar)

    Disposer.register(parentDisposable) {
      EditorFactory.getInstance().releaseEditor(editor)
    }
    return editor
  }

  private fun createAlternateBufferEditor(settings: JBTerminalSystemSettingsProviderBase, parentDisposable: Disposable): EditorEx {
    val document = EditorFactory.getInstance().createDocument("")
    val editor = TerminalUiUtils.createOutputEditor(document, project, settings)
    editor.useTerminalDefaultBackground(parentDisposable = this)
    editor.scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
    editor.scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

    Disposer.register(parentDisposable) {
      EditorFactory.getInstance().releaseEditor(editor)
    }
    return editor
  }

  override fun dispose() {}

  private class TerminalPanel(initialContent: Editor) : Wrapper() {
    private var curEditor: Editor = initialContent

    init {
      setTerminalContent(initialContent)
    }

    val preferredFocusableComponent: JComponent
      get() = curEditor.contentComponent

    fun setTerminalContent(editor: Editor) {
      curEditor = editor
      setContent(editor.component)
    }
  }
}
