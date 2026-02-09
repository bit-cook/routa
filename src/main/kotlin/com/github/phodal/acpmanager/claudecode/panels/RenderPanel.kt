package com.github.phodal.acpmanager.claudecode.panels

import javax.swing.JPanel

/**
 * Base interface for all render panels.
 * Provides common functionality for panel lifecycle management.
 */
interface RenderPanel {
    /**
     * The Swing component for this panel.
     */
    val component: JPanel

    /**
     * Dispose of resources when the panel is no longer needed.
     */
    fun dispose() {}
}

/**
 * Interface for panels that support collapsing/expanding.
 */
interface CollapsiblePanel : RenderPanel {
    /**
     * Whether the panel is currently expanded.
     */
    var isExpanded: Boolean

    /**
     * Toggle the expanded state.
     */
    fun toggle() {
        isExpanded = !isExpanded
    }
}

/**
 * Interface for panels that support streaming updates.
 */
interface StreamingPanel : RenderPanel {
    /**
     * Update the content during streaming.
     */
    fun updateContent(content: String)

    /**
     * Finalize the content when streaming is complete.
     */
    fun finalize(content: String, signature: String? = null)
}

