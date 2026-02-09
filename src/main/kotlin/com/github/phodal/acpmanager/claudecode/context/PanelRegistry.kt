package com.github.phodal.acpmanager.claudecode.context

import com.github.phodal.acpmanager.claudecode.panels.RenderPanel

/**
 * Registry for managing active render panels.
 * Allows handlers to register, retrieve, and remove panels by ID.
 */
class PanelRegistry {
    private val panels = mutableMapOf<String, RenderPanel>()

    /**
     * Register a panel with the given ID.
     */
    fun <T : RenderPanel> register(id: String, panel: T): T {
        panels[id] = panel
        return panel
    }

    /**
     * Get a panel by ID.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : RenderPanel> get(id: String): T? {
        return panels[id] as? T
    }

    /**
     * Remove a panel by ID.
     */
    fun remove(id: String): RenderPanel? {
        return panels.remove(id)
    }

    /**
     * Check if a panel exists.
     */
    fun contains(id: String): Boolean {
        return panels.containsKey(id)
    }

    /**
     * Get all panels of a specific type.
     */
    fun <T : RenderPanel> getAllOfType(clazz: Class<T>): List<T> {
        return panels.values.filter { clazz.isInstance(it) }.map { clazz.cast(it) }
    }

    /**
     * Clear all panels.
     */
    fun clear() {
        panels.values.forEach { it.dispose() }
        panels.clear()
    }

    /**
     * Get the count of registered panels.
     */
    fun size(): Int = panels.size
}

