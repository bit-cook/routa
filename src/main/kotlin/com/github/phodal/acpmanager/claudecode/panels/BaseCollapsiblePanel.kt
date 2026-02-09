package com.github.phodal.acpmanager.claudecode.panels

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Base class for collapsible panels with a header and expandable content.
 */
abstract class BaseCollapsiblePanel(
    protected val headerColor: Color,
    initiallyExpanded: Boolean = false
) : JPanel(), CollapsiblePanel {

    override val component: JPanel get() = this
    override var isExpanded: Boolean = initiallyExpanded
        set(value) {
            field = value
            updateExpandedState()
        }

    protected val headerPanel: JPanel
    protected val headerIcon: JBLabel
    protected val headerTitle: JBLabel
    protected val contentPanel: JPanel

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)

        // Header panel
        headerPanel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        headerIcon = JBLabel(getExpandIcon()).apply {
            foreground = headerColor
            font = font.deriveFont(Font.BOLD)
        }
        headerPanel.add(headerIcon, BorderLayout.WEST)

        headerTitle = JBLabel().apply {
            foreground = headerColor
            font = font.deriveFont(Font.BOLD, font.size2D - 1)
        }
        headerPanel.add(headerTitle, BorderLayout.CENTER)

        add(headerPanel)

        // Content panel (collapsible)
        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(20)
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = initiallyExpanded
        }
        add(contentPanel)

        // Click to toggle
        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggle()
            }
        })
    }

    protected fun getExpandIcon(): String = if (isExpanded) "▼" else "▶"

    protected open fun updateExpandedState() {
        headerIcon.text = getExpandIcon()
        contentPanel.isVisible = isExpanded
        revalidate()
        repaint()
        parent?.revalidate()
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    /**
     * Set the header title text.
     */
    fun setTitle(title: String) {
        headerTitle.text = title
    }

    /**
     * Update the header color.
     */
    fun setHeaderColor(color: Color) {
        headerIcon.foreground = color
        headerTitle.foreground = color
    }
}

