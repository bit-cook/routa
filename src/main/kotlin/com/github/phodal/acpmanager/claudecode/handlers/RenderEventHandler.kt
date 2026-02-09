package com.github.phodal.acpmanager.claudecode.handlers

import com.github.phodal.acpmanager.claudecode.context.RenderContext
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import kotlin.reflect.KClass

/**
 * Base interface for render event handlers.
 * Each handler is responsible for processing specific types of render events.
 */
interface RenderEventHandler {
    /**
     * Check if this handler can process the given event.
     */
    fun canHandle(event: RenderEvent): Boolean

    /**
     * Handle the event and update the UI accordingly.
     */
    fun handle(event: RenderEvent, context: RenderContext)
}

/**
 * Abstract base class for handlers that process specific event types.
 */
abstract class TypedEventHandler<T : RenderEvent>(
    private val eventClass: KClass<T>
) : RenderEventHandler {

    override fun canHandle(event: RenderEvent): Boolean {
        return eventClass.isInstance(event)
    }

    @Suppress("UNCHECKED_CAST")
    override fun handle(event: RenderEvent, context: RenderContext) {
        if (canHandle(event)) {
            handleTyped(event as T, context)
        }
    }

    /**
     * Handle the typed event.
     */
    protected abstract fun handleTyped(event: T, context: RenderContext)
}

/**
 * Handler that can process multiple related event types.
 */
abstract class MultiEventHandler : RenderEventHandler {
    /**
     * List of event classes this handler can process.
     */
    protected abstract val supportedEvents: Set<KClass<out RenderEvent>>

    override fun canHandle(event: RenderEvent): Boolean {
        return supportedEvents.any { it.isInstance(event) }
    }
}

