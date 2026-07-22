package com.aivcsassistant

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class AiVcsAssistantToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean =
        true

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
    private val outputTitleLabel = JBLabel()
    private val outputTitleField = JBTextField().apply {
        isEditable = false
    }
    private val outputDescriptionLabel = JBLabel()
    private val outputDescriptionArea = JBTextArea(10, 28).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val outputTitleRow = copyableRow(outputTitleField) { outputTitleField.text }
    private val outputDescriptionRow = copyableRow(JBScrollPane(outputDescriptionArea)) { outputDescriptionArea.text }
    private val outputPanel = createOutputPanel()
    private val refreshTimer = Timer(750) { updateState() }

    init {
        border = JBUI.Borders.empty(12)
        add(createControlsPanel(), BorderLayout.NORTH)
        add(outputPanel, BorderLayout.CENTER)
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

    private fun createControlsPanel(): JComponent =
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

    private fun createOutputPanel(): JComponent =
        JPanel(GridBagLayout()).apply {
            isVisible = false
            border = JBUI.Borders.emptyTop(8)

            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insetsBottom(4)
            }
            add(outputTitleLabel, constraints)

            constraints.gridy = 1
            constraints.insets = JBUI.insetsBottom(8)
            add(outputTitleRow, constraints)

            constraints.gridy = 2
            constraints.insets = JBUI.insetsBottom(4)
            add(outputDescriptionLabel, constraints)

            constraints.gridy = 3
            constraints.fill = GridBagConstraints.BOTH
            constraints.weighty = 1.0
            constraints.insets = JBUI.emptyInsets()
            add(outputDescriptionRow, constraints)
        }

    private fun copyableRow(content: JComponent, text: () -> String): JComponent =
        JPanel(BorderLayout(8, 0)).apply {
            add(content, BorderLayout.CENTER)
            add(copyButton(text), BorderLayout.EAST)
        }

    private fun copyButton(text: () -> String): JButton =
        JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(text()))
            }
        }

    private fun updateState() {
        val commitRoot = commitToolWindowComponent()
        val pullRequestRoot = pullRequestToolWindowComponent()

        commitButton.isEnabled = commitRoot != null
        pullRequestButton.isEnabled = pullRequestRoot != null
        if (pullRequestRoot == null) {
            hidePullRequestOutput()
        } else if (hasPullRequestOutput()) {
            outputPanel.isVisible = true
        }
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
        hidePullRequestOutput()
        GenerateCommitMessageAction().generate(project, commitRoot, dataContext)
    }

    private fun generatePullRequestContent() {
        val pullRequestRoot = pullRequestToolWindowComponent()
        if (pullRequestRoot == null) {
            AiVcsAssistantSupport.notify(project, "Open the Pull Requests or Merge Requests tool window first.", NotificationType.WARNING)
            updateState()
            return
        }

        GeneratePullRequestContentAction().generate(project, pullRequestRoot) { title, description ->
            showPullRequestOutput(title, description)
        }
    }

    private fun hidePullRequestOutput() {
        outputPanel.isVisible = false
        revalidate()
        repaint()
    }

    private fun hasPullRequestOutput(): Boolean =
        outputTitleField.text.isNotBlank() || outputDescriptionArea.text.isNotBlank()

    private fun showPullRequestOutput(title: String, description: String) {
        outputTitleLabel.text = "Pull request title"
        outputTitleField.text = title
        outputDescriptionLabel.text = "Pull request description"
        outputDescriptionLabel.isVisible = true
        outputDescriptionArea.text = description
        outputDescriptionArea.caretPosition = 0
        outputDescriptionRow.isVisible = true
        outputPanel.isVisible = true
        revalidate()
        repaint()
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

    fun update(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.setAvailable(true, null)
    }
}
