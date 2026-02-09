package com.github.phodal.acpmanager.claudecode.handlers

import com.github.phodal.acpmanager.claudecode.context.RenderContext
import com.github.phodal.acpmanager.claudecode.panels.MessagePanel
import com.github.phodal.acpmanager.claudecode.panels.SimpleStreamingPanel
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import kotlin.reflect.KClass

/**
 * Handler for message-related events (UserMessage, MessageStart, MessageChunk, MessageEnd).
 */
class MessageEventHandler : MultiEventHandler() {

    override val supportedEvents: Set<KClass<out RenderEvent>> = setOf(
        RenderEvent.UserMessage::class,
        RenderEvent.MessageStart::class,
        RenderEvent.MessageChunk::class,
        RenderEvent.MessageEnd::class
    )

    override fun handle(event: RenderEvent, context: RenderContext) {
        when (event) {
            is RenderEvent.UserMessage -> handleUserMessage(event, context)
            is RenderEvent.MessageStart -> handleStart(context)
            is RenderEvent.MessageChunk -> handleChunk(event, context)
            is RenderEvent.MessageEnd -> handleEnd(event, context)
            else -> {}
        }
    }

    private fun handleUserMessage(event: RenderEvent.UserMessage, context: RenderContext) {
        val panel = MessagePanel(
            name = "You",
            content = event.content,
            timestamp = event.timestamp,
            headerColor = context.colors.userFg
        )
        context.addPanel(panel.component)
        context.scrollToBottom()
    }

    private fun handleStart(context: RenderContext) {
        context.messageBuffer.clear()

        val panel = SimpleStreamingPanel("Assistant", context.colors.messageFg)
        context.currentMessagePanel = panel
        context.addPanel(panel.component)
        context.scrollToBottom()
    }

    private fun handleChunk(event: RenderEvent.MessageChunk, context: RenderContext) {
        context.messageBuffer.append(event.content)
        (context.currentMessagePanel as? SimpleStreamingPanel)?.updateContent(
            context.messageBuffer.toString()
        )
        context.scrollToBottom()
    }

    private fun handleEnd(event: RenderEvent.MessageEnd, context: RenderContext) {
        (context.currentMessagePanel as? SimpleStreamingPanel)?.finalize(event.fullContent, null)
        context.currentMessagePanel = null
        context.messageBuffer.clear()
        context.scrollToBottom()
    }
}

