package com.aivcsassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

val DEFAULT_PULL_REQUEST_DESCRIPTION_TEMPLATE: String = """
    ## 📌 Summary

    <!-- Provide a short summary explaining the purpose of this pull request -->

    ## 🔍 Related Issues / Tickets

    <!-- Link related issues or tickets using "Closes #123", "Fixes JIRA-456", etc. -->
    Closes #

    ## 🧪 Testing Steps

    <!-- Describe how a reviewer can test these changes. Include setup or edge cases if necessary -->
    1.
    2.
    3.

    ## 📸 Screenshots / Diagrams (if applicable)

    <!-- Attach screenshots or diagrams to help reviewers understand the changes visually -->
    <!-- Example: before/after UI changes, architecture diagrams, etc. -->
    ![Screenshot](url)

    ## 📂 Type of Change

    <!-- Check the relevant option(s) -->

    - [ ] 🚀 Feature
    - [ ] 🐛 Bugfix
    - [ ] ♻️ Refactor
    - [ ] 🧪 Test
    - [ ] 📝 Documentation
    - [ ] 🔧 Chore / Maintenance

    ## ✅ Checklist

    <!-- Ensure all the items are checked before requesting a review -->

    - [ ] My code follows the team’s coding guidelines and style
    - [ ] I have linted and formatted my code
    - [ ] I have tested the changes locally
    - [ ] I have updated relevant documentation (README, API docs, etc.)
    - [ ] I have included screenshots/diagrams where applicable
    - [ ] I have squashed commits into a single, meaningful commit

    ---

    > 📝 Tip: Use `[WIP]` or open as a Draft if this PR is not ready for review.
""".trimIndent()

@State(name = "AiVcsAssistantSettings", storages = [Storage("aiVcsAssistant.xml")])
class AiVcsAssistantSettings : PersistentStateComponent<AiVcsAssistantSettings.State> {
    data class State(
        var provider: String = "Codex",
        var codexExecutable: String = "codex",
        var antigravityExecutable: String = "agy",
        var githubCopilotExecutable: String = "copilot",
        var claudeExecutable: String = "claude",
        var cursorExecutable: String = "cursor-agent",
        var customProviderName: String = "Custom",
        var customExecutable: String = "",
        var customArguments: String = "--print {prompt}",
        var customReadOutputFromFile: Boolean = false,
        var customPromptViaStdin: Boolean = false,
        var conventionalCommits: Boolean = false,
        var includeBody: Boolean = false,
        var timeoutSeconds: Int = 120,
        var prefixCommitMessageWithBranchIdentifier: Boolean = true,
        var branchIdentifierRegex: String = "[A-Z][A-Z0-9]+-\\d+",
        var commitMessageTemplate: String = "{branch}: {message}",
        var defaultPullRequestTargetBranch: String = "main",
        var pullRequestSubjectTemplate: String = "{branch}: {title}",
        var pullRequestDescriptionTemplate: String = DEFAULT_PULL_REQUEST_DESCRIPTION_TEMPLATE,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        if (state.githubCopilotExecutable == "gh") {
            state.githubCopilotExecutable = "copilot"
        }
        this.state = state
    }

    companion object {
        fun getInstance(): AiVcsAssistantSettings =
            ApplicationManager.getApplication().getService(AiVcsAssistantSettings::class.java)
    }
}
