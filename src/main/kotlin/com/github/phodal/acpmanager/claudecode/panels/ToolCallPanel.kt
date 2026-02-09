package com.github.phodal.acpmanager.claudecode.panels

import com.agentclientprotocol.model.ToolCallStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Collapsible panel for displaying tool call information.
 * Shows tool name, status, parameters (input), and output.
 * Can be expanded to view full details.
 */
class ToolCallPanel(
    private val toolName: String,
    private var title: String,
    accentColor: Color
) : BaseCollapsiblePanel(accentColor, initiallyExpanded = false) {

    private val statusIcon: JBLabel
    private var currentStatus: ToolCallStatus = ToolCallStatus.PENDING
    private var isCompleted = false

    // Content sections
    private val inputSection: CollapsibleSection
    private val outputSection: CollapsibleSection

    init {
        // Add status icon before the expand icon
        statusIcon = JBLabel("○").apply {
            foreground = headerColor
            font = font.deriveFont(Font.BOLD)
        }
        headerPanel.add(statusIcon, BorderLayout.WEST)

        // Move expand icon to after status
        headerPanel.remove(headerIcon)
        val iconPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(statusIcon)
            add(headerIcon)
        }
        headerPanel.add(iconPanel, BorderLayout.WEST)

        headerTitle.text = "🔧 $title"

        // Input section (parameters)
        inputSection = CollapsibleSection("📥 Input", UIUtil.getLabelDisabledForeground())
        contentPanel.add(inputSection)

        // Output section
        outputSection = CollapsibleSection("📤 Output", UIUtil.getLabelDisabledForeground())
        outputSection.isVisible = false
        contentPanel.add(outputSection)
    }

    /**
     * Update the tool call status.
     */
    fun updateStatus(status: ToolCallStatus, newTitle: String? = null) {
        if (isCompleted) return
        currentStatus = status
        newTitle?.let {
            title = it
            headerTitle.text = "🔧 $it"
        }
        statusIcon.text = when (status) {
            ToolCallStatus.IN_PROGRESS -> "▶"
            ToolCallStatus.PENDING -> "○"
            else -> "■"
        }
        revalidate()
        repaint()
    }

    /**
     * Update the input parameters.
     */
    fun updateParameters(params: String) {
        if (isCompleted) return
        inputSection.setContent(params)
        inputSection.isVisible = params.isNotEmpty()
        revalidate()
        repaint()
        parent?.revalidate()
    }

    /**
     * Complete the tool call with final status and output.
     */
    fun complete(status: ToolCallStatus, output: String?) {
        isCompleted = true
        currentStatus = status

        val (icon, color) = if (status == ToolCallStatus.COMPLETED) {
            "✓" to JBColor(Color(0x2E7D32), Color(0x81C784))
        } else {
            "✗" to JBColor.RED
        }

        statusIcon.text = icon
        statusIcon.foreground = color
        setHeaderColor(color)

        // Show output if available
        output?.let {
            outputSection.setContent(it)
            outputSection.isVisible = true
        }

        revalidate()
        repaint()
    }

    /**
     * Get the current status.
     */
    fun getStatus(): ToolCallStatus = currentStatus
}

