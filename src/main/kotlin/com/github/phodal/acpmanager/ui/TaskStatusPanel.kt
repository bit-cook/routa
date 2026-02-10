package com.github.phodal.acpmanager.ui

import com.agentclientprotocol.model.ToolCallStatus
import com.github.phodal.acpmanager.ui.renderer.TaskItem
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Compact task status panel displayed above the input area.
 * Shows a summary of current tasks with status icons.
 */
class TaskStatusPanel : JPanel(BorderLayout()) {

    private val tasksContainer = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        isOpaque = false
    }

    private var currentTasks: List<TaskItem> = emptyList()

    init {
        isOpaque = true
        background = JBColor(0xF5F5F5, 0x3C3F41)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(4, 8)
        )
        isVisible = false  // Hidden by default

        add(tasksContainer, BorderLayout.CENTER)
    }

    /**
     * Update the displayed tasks.
     */
    fun updateTasks(tasks: List<TaskItem>) {
        currentTasks = tasks
        tasksContainer.removeAll()

        if (tasks.isEmpty()) {
            isVisible = false
            return
        }

        // Count tasks by status
        val completed = tasks.count { it.status == ToolCallStatus.COMPLETED }
        val inProgress = tasks.count { it.status == ToolCallStatus.IN_PROGRESS }
        val failed = tasks.count { it.status == ToolCallStatus.FAILED }
        val pending = tasks.count { it.status == ToolCallStatus.PENDING }

        // Add summary label
        val summaryLabel = JBLabel("Tasks: ").apply {
            font = UIUtil.getLabelFont().deriveFont(12f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        tasksContainer.add(summaryLabel)

        // Add status counts with icons
        if (completed > 0) {
            tasksContainer.add(createStatusChip(AllIcons.RunConfigurations.TestPassed, "$completed done"))
        }
        if (inProgress > 0) {
            tasksContainer.add(createStatusChip(AllIcons.Process.Step_1, "$inProgress running"))
        }
        if (pending > 0) {
            tasksContainer.add(createStatusChip(AllIcons.RunConfigurations.TestNotRan, "$pending pending"))
        }
        if (failed > 0) {
            tasksContainer.add(createStatusChip(AllIcons.RunConfigurations.TestFailed, "$failed failed"))
        }

        // Show current task name if there's one in progress
        val currentTask = tasks.find { it.status == ToolCallStatus.IN_PROGRESS }
        if (currentTask != null) {
            val taskLabel = JBLabel("• ${truncateTitle(currentTask.title, 40)}").apply {
                font = UIUtil.getLabelFont().deriveFont(12f)
                foreground = UIUtil.getLabelForeground()
            }
            tasksContainer.add(taskLabel)
        }

        isVisible = true
        revalidate()
        repaint()
    }

    private fun createStatusChip(icon: Icon, text: String): JBLabel {
        return JBLabel(text, icon, JBLabel.LEFT).apply {
            font = UIUtil.getLabelFont().deriveFont(11f)
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 2)
        }
    }

    private fun truncateTitle(title: String, maxLength: Int): String {
        return if (title.length > maxLength) {
            title.take(maxLength - 3) + "..."
        } else {
            title
        }
    }

    /**
     * Clear all tasks and hide the panel.
     */
    fun clear() {
        currentTasks = emptyList()
        tasksContainer.removeAll()
        isVisible = false
        revalidate()
        repaint()
    }
}

