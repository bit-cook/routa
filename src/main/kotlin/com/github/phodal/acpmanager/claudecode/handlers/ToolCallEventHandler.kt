package com.github.phodal.acpmanager.claudecode.handlers

import com.github.phodal.acpmanager.claudecode.context.RenderContext
import com.github.phodal.acpmanager.claudecode.panels.ToolCallPanel
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import kotlin.reflect.KClass

/**
 * Handler for tool call events (ToolCallStart, ToolCallUpdate, ToolCallEnd, ToolCallParameterUpdate).
 * Excludes Task tool calls which are handled by TaskEventHandler.
 */
class ToolCallEventHandler : MultiEventHandler() {

    override val supportedEvents: Set<KClass<out RenderEvent>> = setOf(
        RenderEvent.ToolCallStart::class,
        RenderEvent.ToolCallUpdate::class,
        RenderEvent.ToolCallEnd::class,
        RenderEvent.ToolCallParameterUpdate::class
    )

    override fun canHandle(event: RenderEvent): Boolean {
        // Don't handle Task tool calls - those go to TaskEventHandler
        if (event is RenderEvent.ToolCallStart && isTaskToolCall(event)) {
            return false
        }
        return super.canHandle(event)
    }

    override fun handle(event: RenderEvent, context: RenderContext) {
        when (event) {
            is RenderEvent.ToolCallStart -> handleStart(event, context)
            is RenderEvent.ToolCallUpdate -> handleUpdate(event, context)
            is RenderEvent.ToolCallEnd -> handleEnd(event, context)
            is RenderEvent.ToolCallParameterUpdate -> handleParameterUpdate(event, context)
            else -> {}
        }
    }

    private fun isTaskToolCall(event: RenderEvent.ToolCallStart): Boolean {
        return event.kind?.equals("Task", ignoreCase = true) == true ||
               event.toolName.equals("Task", ignoreCase = true)
    }

    private fun handleStart(event: RenderEvent.ToolCallStart, context: RenderContext) {
        val panel = ToolCallPanel(
            toolName = event.toolName,
            title = event.title ?: event.toolName,
            accentColor = context.colors.toolFg
        )
        context.panelRegistry.register(event.toolCallId, panel)
        context.addPanel(panel.component)
        context.scrollToBottom()
    }

    private fun handleUpdate(event: RenderEvent.ToolCallUpdate, context: RenderContext) {
        val panel = context.panelRegistry.get<ToolCallPanel>(event.toolCallId)
        panel?.updateStatus(event.status, event.title)
    }

    private fun handleEnd(event: RenderEvent.ToolCallEnd, context: RenderContext) {
        val panel = context.panelRegistry.get<ToolCallPanel>(event.toolCallId)
        panel?.complete(event.status, event.output)
        context.scrollToBottom()
    }

    private fun handleParameterUpdate(event: RenderEvent.ToolCallParameterUpdate, context: RenderContext) {
        val panel = context.panelRegistry.get<ToolCallPanel>(event.toolCallId)
        panel?.updateParameters(event.partialParameters)
    }
}

