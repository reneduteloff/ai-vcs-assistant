package com.aivcsassistant

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AiVcsAssistantConfigurable : Configurable {
    private val providerComboBox = JComboBox(
        arrayOf(
            AiVcsAssistantSupport.PROVIDER_CODEX,
            AiVcsAssistantSupport.PROVIDER_ANTIGRAVITY,
            AiVcsAssistantSupport.PROVIDER_GITHUB_COPILOT,
            AiVcsAssistantSupport.PROVIDER_CLAUDE,
            AiVcsAssistantSupport.PROVIDER_CURSOR,
            AiVcsAssistantSupport.PROVIDER_CUSTOM,
        ),
    )
    private val executableField = JBTextField()
    private val antigravityExecutableField = JBTextField()
    private val githubCopilotExecutableField = JBTextField()
    private val claudeExecutableField = JBTextField()
    private val cursorExecutableField = JBTextField()
    private val customProviderNameField = JBTextField()
    private val customExecutableField = JBTextField()
    private val customArgumentsField = JBTextField()
    private val customOutputFileCheckBox = JBCheckBox("Read custom provider response from {outputFile}")
    private val customPromptStdinCheckBox = JBCheckBox("Send prompt to custom provider via stdin")
    private val providerOptionsPanel = JPanel(BorderLayout())
    private val bodyCheckBox = JBCheckBox("Allow a message body when useful")
    private val prefixBranchCheckBox = JBCheckBox("Prefix commit message with branch identifier")
    private val branchIdentifierRegexField = JBTextField()
    private val commitMessageTemplateArea = templateArea()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(120, 15, 600, 15))
    private var panel: JPanel? = null

    init {
        providerComboBox.addActionListener { updateProviderOptions() }
    }

    override fun getDisplayName(): String = "AI VCS Assistant"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(sectionLabel("AI Provider"))
            .addLabeledComponent(JBLabel("Provider:"), providerComboBox, 1, false)
            .addComponent(providerOptionsPanel)
            .addLabeledComponent(JBLabel("Timeout in seconds:"), timeoutSpinner)
            .addComponent(sectionLabel("Commit Message"))
            .addComponent(bodyCheckBox)
            .addComponent(prefixBranchCheckBox)
            .addLabeledComponent(JBLabel("Branch identifier regular expression:"), branchIdentifierRegexField, 1, false)
            .addLabeledComponent(JBLabel("Commit message template:"), JBScrollPane(commitMessageTemplateArea), 1, false)
            .addComponent(JBLabel("Supported placeholders: {branch}, {message}, {provider}"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = AiVcsAssistantSettings.getInstance().state
        val selectedProvider = providerComboBox.selectedItem as? String ?: AiVcsAssistantSupport.PROVIDER_CODEX
        return selectedProvider != state.provider ||
            executableField.text.trim() != state.codexExecutable ||
            antigravityExecutableField.text.trim() != state.antigravityExecutable ||
            githubCopilotExecutableField.text.trim() != state.githubCopilotExecutable ||
            claudeExecutableField.text.trim() != state.claudeExecutable ||
            cursorExecutableField.text.trim() != state.cursorExecutable ||
            customProviderNameField.text.trim() != state.customProviderName ||
            customExecutableField.text.trim() != state.customExecutable ||
            customArgumentsField.text.trim() != state.customArguments ||
            customOutputFileCheckBox.isSelected != state.customReadOutputFromFile ||
            customPromptStdinCheckBox.isSelected != state.customPromptViaStdin ||
            bodyCheckBox.isSelected != state.includeBody ||
            prefixBranchCheckBox.isSelected != state.prefixCommitMessageWithBranchIdentifier ||
            branchIdentifierRegexField.text.trim() != state.branchIdentifierRegex ||
            commitMessageTemplateArea.text.trim() != state.commitMessageTemplate ||
            (timeoutSpinner.value as Int) != state.timeoutSeconds
    }

    override fun apply() {
        val state = AiVcsAssistantSettings.getInstance().state
        state.provider = providerComboBox.selectedItem as? String ?: AiVcsAssistantSupport.PROVIDER_CODEX
        state.codexExecutable = executableField.text.trim().ifBlank { "codex" }
        state.antigravityExecutable = antigravityExecutableField.text.trim().ifBlank { "agy" }
        state.githubCopilotExecutable = githubCopilotExecutableField.text.trim().ifBlank { "copilot" }
        state.claudeExecutable = claudeExecutableField.text.trim().ifBlank { "claude" }
        state.cursorExecutable = cursorExecutableField.text.trim().ifBlank { "cursor-agent" }
        state.customProviderName = customProviderNameField.text.trim().ifBlank { "Custom" }
        state.customExecutable = customExecutableField.text.trim()
        state.customArguments = customArgumentsField.text.trim().ifBlank { "--print {prompt}" }
        state.customReadOutputFromFile = customOutputFileCheckBox.isSelected
        state.customPromptViaStdin = customPromptStdinCheckBox.isSelected
        state.includeBody = bodyCheckBox.isSelected
        state.prefixCommitMessageWithBranchIdentifier = prefixBranchCheckBox.isSelected
        state.branchIdentifierRegex = branchIdentifierRegexField.text.trim().ifBlank { "[A-Z][A-Z0-9]+-\\d+" }
        state.commitMessageTemplate = commitMessageTemplateArea.text.trim().ifBlank { "{branch}: {message}" }
        state.timeoutSeconds = timeoutSpinner.value as Int
    }

    override fun reset() {
        val state = AiVcsAssistantSettings.getInstance().state
        providerComboBox.selectedItem = state.provider
        executableField.text = state.codexExecutable
        antigravityExecutableField.text = state.antigravityExecutable
        githubCopilotExecutableField.text = state.githubCopilotExecutable
        claudeExecutableField.text = state.claudeExecutable
        cursorExecutableField.text = state.cursorExecutable
        customProviderNameField.text = state.customProviderName
        customExecutableField.text = state.customExecutable
        customArgumentsField.text = state.customArguments
        customOutputFileCheckBox.isSelected = state.customReadOutputFromFile
        customPromptStdinCheckBox.isSelected = state.customPromptViaStdin
        bodyCheckBox.isSelected = state.includeBody
        prefixBranchCheckBox.isSelected = state.prefixCommitMessageWithBranchIdentifier
        branchIdentifierRegexField.text = state.branchIdentifierRegex
        commitMessageTemplateArea.text = state.commitMessageTemplate
        timeoutSpinner.value = state.timeoutSeconds
        updateProviderOptions()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun sectionLabel(text: String): JBLabel = JBLabel("<html><b>$text</b></html>")

    private fun updateProviderOptions() {
        providerOptionsPanel.removeAll()
        providerOptionsPanel.add(
            when (providerComboBox.selectedItem as? String) {
                AiVcsAssistantSupport.PROVIDER_ANTIGRAVITY -> antigravityOptionsPanel()
                AiVcsAssistantSupport.PROVIDER_GITHUB_COPILOT -> githubCopilotOptionsPanel()
                AiVcsAssistantSupport.PROVIDER_CLAUDE -> claudeOptionsPanel()
                AiVcsAssistantSupport.PROVIDER_CURSOR -> cursorOptionsPanel()
                AiVcsAssistantSupport.PROVIDER_CUSTOM -> customOptionsPanel()
                else -> codexOptionsPanel()
            },
            BorderLayout.CENTER,
        )
        providerOptionsPanel.revalidate()
        providerOptionsPanel.repaint()
    }

    private fun codexOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Codex executable:"), executableField, 1, false)
            .addComponent(JBLabel("Default: codex. Enter an absolute path only when auto-detection should be overridden."))
            .panel

    private fun antigravityOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Antigravity executable:"), antigravityExecutableField, 1, false)
            .addComponent(JBLabel("Default: agy. Enter an absolute path only when auto-detection should be overridden."))
            .panel

    private fun githubCopilotOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("GitHub Copilot executable:"), githubCopilotExecutableField, 1, false)
            .addComponent(JBLabel("Default: copilot. Enter an absolute path only when auto-detection should be overridden."))
            .panel

    private fun claudeOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Claude executable:"), claudeExecutableField, 1, false)
            .addComponent(JBLabel("Default: claude. Enter an absolute path only when auto-detection should be overridden."))
            .panel

    private fun cursorOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Cursor executable:"), cursorExecutableField, 1, false)
            .addComponent(JBLabel("Default: cursor-agent. Enter an absolute path only when auto-detection should be overridden."))
            .panel

    private fun customOptionsPanel(): JPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Custom provider name:"), customProviderNameField, 1, false)
            .addLabeledComponent(JBLabel("Custom executable:"), customExecutableField, 1, false)
            .addLabeledComponent(JBLabel("Custom arguments:"), customArgumentsField, 1, false)
            .addComponent(customOutputFileCheckBox)
            .addComponent(customPromptStdinCheckBox)
            .addComponent(JBLabel("Custom placeholders: {prompt}, {promptFile}, {outputFile}, {timeoutSeconds}"))
            .panel

    private fun templateArea(rows: Int = 3): JBTextArea =
        JBTextArea(rows, 72).apply {
            lineWrap = true
            wrapStyleWord = true
        }
}
