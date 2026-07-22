package com.aivcsassistant

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class AiVcsAssistantToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean =
        AiVcsAssistantToolWindowVisibility.isRelevantVcsToolWindowVisible(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AiVcsAssistantToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class AiVcsAssistantToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val commitButton = JButton("Generate Commit Message").apply {
        addActionListener { generateCommitMessage() }
    }
    private val pullRequestButton = JButton("Generate Pull Request Title and Description").apply {
        addActionListener { generatePullRequestContent() }
    }
    private val statusLabel = JBLabel()
    private val refreshTimer = Timer(750) { updateState() }

    init {
        border = JBUI.Borders.empty(12)
        add(createContent(), BorderLayout.NORTH)
        refreshTimer.isRepeats = true
        updateState()
    }

    override fun addNotify() {
        super.addNotify()
        updateState()
        refreshTimer.start()
    }

    override fun removeNotify() {
        refreshTimer.stop()
        super.removeNotify()
    }

    private fun createContent(): JComponent =
        JPanel(GridBagLayout()).apply {
            val constraints = GridBagConstraints().apply {
                gridx = 0
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insetsBottom(8)
            }

            add(commitButton, constraints)
            constraints.gridy = 1
            add(pullRequestButton, constraints)
            constraints.gridy = 2
            add(statusLabel, constraints)
        }

    private fun updateState() {
        val commitRoot = commitToolWindowComponent()
        val pullRequestRoot = pullRequestToolWindowComponent()

        commitButton.isEnabled = commitRoot != null
        pullRequestButton.isEnabled = pullRequestRoot != null
        statusLabel.text = when {
            commitRoot != null && pullRequestRoot != null -> "Commit and pull request context detected."
            commitRoot != null -> "Commit context detected."
            pullRequestRoot != null -> "Pull request context detected."
            else -> "Open Commit or Pull Requests to use this panel."
        }
    }

    private fun generateCommitMessage() {
        val commitRoot = commitToolWindowComponent()
        if (commitRoot == null) {
            AiVcsAssistantSupport.notify(project, "Open the Commit tool window first.", NotificationType.WARNING)
            updateState()
            return
        }

        val dataContext = DataManager.getInstance().getDataContext(commitRoot)
        GenerateCommitMessageAction().generate(project, commitRoot, dataContext)
    }

    private fun generatePullRequestContent() {
        val pullRequestRoot = pullRequestToolWindowComponent()
        if (pullRequestRoot == null) {
            AiVcsAssistantSupport.notify(project, "Open the Pull Requests or Merge Requests tool window first.", NotificationType.WARNING)
            updateState()
            return
        }

        GeneratePullRequestContentAction().generate(project, pullRequestRoot)
    }

    private fun commitToolWindowComponent(): Component? =
        visibleToolWindowComponent(COMMIT_TOOL_WINDOW_ID)

    private fun pullRequestToolWindowComponent(): Component? =
        PULL_REQUEST_TOOL_WINDOW_IDS.firstNotNullOfOrNull(::visibleToolWindowComponent)

    private fun visibleToolWindowComponent(id: String): Component? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return null
        if (!toolWindow.isVisible) return null
        return toolWindow.component.takeIf(Component::isShowing)
            ?: toolWindow.contentManager.component.takeIf(Component::isShowing)
            ?: toolWindow.component
    }

    private companion object {
        private const val COMMIT_TOOL_WINDOW_ID = "Commit"
        private val PULL_REQUEST_TOOL_WINDOW_IDS = listOf("Pull Requests", "Merge Requests")
    }
}

object AiVcsAssistantToolWindowVisibility {
    const val TOOL_WINDOW_ID = "AI VCS Assistant"
    private const val COMMIT_TOOL_WINDOW_ID = "Commit"
    private val PULL_REQUEST_TOOL_WINDOW_IDS = listOf("Pull Requests", "Merge Requests")

    fun update(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
            val available = isRelevantVcsToolWindowVisible(project)
            toolWindow.setAvailable(available, null)
        }
    }

    fun isRelevantVcsToolWindowVisible(project: Project): Boolean =
        isVisible(project, COMMIT_TOOL_WINDOW_ID) ||
            PULL_REQUEST_TOOL_WINDOW_IDS.any { isVisible(project, it) }

    private fun isVisible(project: Project, id: String): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return false
        return toolWindow.isVisible || hasShowingComponent(toolWindow.component)
    }

    private fun hasShowingComponent(component: Component?): Boolean {
        if (component == null) return false
        if (component.isShowing) return true
        if (component is Container) {
            return component.components.any(::hasShowingComponent)
        }
        return false
    }
}
