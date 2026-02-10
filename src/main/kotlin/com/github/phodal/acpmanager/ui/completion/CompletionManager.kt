package com.github.phodal.acpmanager.ui.completion

import com.github.phodal.acpmanager.ui.mention.FileMentionProvider
import com.github.phodal.acpmanager.ui.mention.MentionProvider
import com.github.phodal.acpmanager.ui.mention.SymbolMentionProvider
import com.github.phodal.acpmanager.ui.mention.TabMentionProvider
import com.github.phodal.acpmanager.ui.slash.CommandCompletionHandler
import com.github.phodal.acpmanager.ui.slash.SlashCommandRegistry
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea
import java.awt.event.KeyEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private val log = logger<CompletionManager>()

/**
 * Orchestrates completion handlers for the chat input area.
 * Manages @ mention completion and / command completion.
 */
class CompletionManager(
    private val project: Project,
    private val inputArea: JBTextArea,
    private val onMentionInserted: ((com.github.phodal.acpmanager.ui.mention.MentionItem) -> Unit)? = null
) {
    private val mentionHandler: MentionCompletionHandler
    private val commandHandler: CommandCompletionHandler

    init {
        // Initialize mention providers
        val providers = listOf(
            FileMentionProvider(project),
            SymbolMentionProvider(project),
            TabMentionProvider(project)
        )

        mentionHandler = MentionCompletionHandler(inputArea, providers, onMentionInserted)

        // Initialize command handler
        commandHandler = CommandCompletionHandler(inputArea, SlashCommandRegistry.getInstance())

        // Add document listener for text changes
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                handleTextChange()
            }

            override fun removeUpdate(e: DocumentEvent) {
                handleTextChange()
            }

            override fun changedUpdate(e: DocumentEvent) {
                handleTextChange()
            }
        })
    }

    /**
     * Handle text change in the input area.
     */
    private fun handleTextChange() {
        val text = inputArea.text
        val caretPos = inputArea.caretPosition
        mentionHandler.handleTextChange(text, caretPos)
        commandHandler.handleTextChange(text, caretPos)
    }

    /**
     * Handle key press in the input area.
     * Returns true if the event was consumed by a completion handler.
     */
    fun handleKeyPress(e: KeyEvent): Boolean {
        // Try command handler first (/ commands)
        if (commandHandler.handleKeyPress(e)) {
            return true
        }

        // Try mention handler (@ mentions)
        if (mentionHandler.handleKeyPress(e)) {
            return true
        }

        return false
    }

    /**
     * Close all active popups.
     */
    fun closeAllPopups() {
        mentionHandler.closePopup()
        commandHandler.closePopup()
    }
}

