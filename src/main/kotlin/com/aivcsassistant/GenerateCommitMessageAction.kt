package com.aivcsassistant

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Path
import javax.swing.text.JTextComponent

class GenerateCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageField = findCommitMessageField(e)
        if (messageField == null) {
            AiVcsAssistantSupport.notify(project, "Open the Commit tool window and place the cursor in the commit-message field.", NotificationType.WARNING)
            return
        }

        val changes = selectedChanges(e)
        if (changes.isEmpty()) {
            AiVcsAssistantSupport.notify(project, "No changes are selected in the Commit tool window.", NotificationType.WARNING)
            return
        }

        val absolutePaths = changes
            .map { ChangesUtil.getFilePath(it).path }
            .distinct()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Commit Message",
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                var repositoryRoot: Path? = null
                try {
                    indicator.text = "Determining Git repository"
                    val root = determineSingleRepository(absolutePaths)
                    repositoryRoot = root
                    val relativePaths = absolutePaths.map { absolute ->
                        root.relativize(Path.of(absolute).toAbsolutePath().normalize()).toString()
                    }

                    indicator.text = "Reading selected Git changes"
                    val diff = AiVcsAssistantSupport.createSelectedChangesDiff(root, relativePaths)
                    if (diff.isBlank()) {
                        throw IllegalStateException(
                            "Git returned an empty diff. Untracked files and partially selected lines are not supported yet.",
                        )
                    }

                    indicator.checkCanceled()
                    indicator.text = "Asking ${AiVcsAssistantSupport.currentProviderName()} for a commit message"
                    val settings = AiVcsAssistantSettings.getInstance().state
                    val generated = AiVcsAssistantSupport.runProvider(root, buildPrompt(settings, diff), indicator)
                    val semanticMessage = stripConventionalCommitPrefix(AiVcsAssistantSupport.sanitize(generated))
                    if (semanticMessage.isBlank()) {
                        throw IllegalStateException("${AiVcsAssistantSupport.currentProviderName(settings)} returned an empty commit message.")
                    }
                    val branchName = AiVcsAssistantSupport.currentBranch(root)
                    val branchIdentifier = if (settings.prefixCommitMessageWithBranchIdentifier) {
                        AiVcsAssistantSupport.extractBranchIdentifier(branchName, settings.branchIdentifierRegex)
                    } else {
                        ""
                    }
                    val message = if (settings.prefixCommitMessageWithBranchIdentifier) {
                        AiVcsAssistantSupport.applyTemplate(
                            settings.commitMessageTemplate,
                            mapOf(
                                "branch" to branchIdentifier,
                                "message" to semanticMessage,
                                "provider" to AiVcsAssistantSupport.currentProviderName(settings),
                            ),
                            semanticMessage,
                        )
                    } else {
                        semanticMessage
                    }

                    ApplicationManager.getApplication().invokeLater {
                        messageField.text = message
                        messageField.requestFocusInWindow()
                        AiVcsAssistantSupport.notify(
                            project,
                            "Commit message generated. Review or edit it before committing.",
                            NotificationType.INFORMATION,
                        )
                    }
                } catch (ex: AiVcsAssistantSupport.ProviderLoginRequiredException) {
                    AiVcsAssistantSupport.notifyLoginRequired(project, ex, repositoryRoot ?: Path.of(project.basePath ?: "."))
                } catch (ex: AiVcsAssistantSupport.ProviderSetupRequiredException) {
                    AiVcsAssistantSupport.notifySetupRequired(project, ex, repositoryRoot ?: Path.of(project.basePath ?: "."))
                } catch (ex: Exception) {
                    AiVcsAssistantSupport.notify(project, ex.message ?: ex.javaClass.simpleName, NotificationType.ERROR)
                }
            }
        })
    }

    private fun selectedChanges(e: AnActionEvent): List<Change> {
        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
            ?: return emptyList()

        val handlerClass = workflowHandler.javaClass

        val ui = generateSequence(handlerClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz ->
                runCatching {
                    clazz.getDeclaredField("ui").apply {
                        isAccessible = true
                    }.get(workflowHandler)
                }.getOrNull()
            }
            .firstOrNull()
            ?: return emptyList()

        val includedChangesMethod = ui.javaClass.methods
            .firstOrNull {
                it.name == "getIncludedChanges" &&
                        it.parameterCount == 0
            }
            ?: return emptyList()

        val includedChanges = includedChangesMethod.invoke(ui)

        return when (includedChanges) {
            is Collection<*> -> includedChanges.filterIsInstance<Change>()
            else -> emptyList()
        }
    }

    private fun findCommitMessageField(e: AnActionEvent): JTextComponent? {
        val contextComponent = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        findTextComponent(contextComponent)?.let { return it }

        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        findTextComponent(focused)?.let { return it }

        var parent = focused?.parent
        while (parent != null) {
            findTextComponent(parent)?.let { return it }
            parent = parent.parent
        }
        return null
    }

    private fun findTextComponent(component: Component?): JTextComponent? {
        if (component is JTextComponent && component.isEditable) return component
        if (component is Container) {
            for (child in component.components) {
                findTextComponent(child)?.let { return it }
            }
        }
        return null
    }

    private fun determineSingleRepository(absolutePaths: List<String>): Path {
        val roots = absolutePaths.map { absolutePath ->
            val file = File(absolutePath)
            val startDirectory = if (file.isDirectory) file else file.parentFile
                ?: throw IllegalStateException("Could not determine a directory for $absolutePath")
            AiVcsAssistantSupport.gitRepositoryRoot(startDirectory)
        }.toSet()

        if (roots.size != 1) {
            throw IllegalStateException("Select changes from exactly one Git repository. Selected repositories: ${roots.size}.")
        }
        return roots.single()
    }

    private fun buildPrompt(settings: AiVcsAssistantSettings.State, diff: String): String {
        val body = if (settings.includeBody) {
            "A short body is allowed only when it adds important why/context."
        } else {
            "Return exactly one subject line and no body."
        }
        return """
            Generate a specific and concrete Git commit message based only on the supplied diff.
            Use a concise imperative Git commit subject.
            Do not use a Conventional Commits type or scope such as fix:, feat:, style(build):, or chore(deps):.
            $body
            Avoid generic wording such as:
            - Update code
            - Make changes
            - Improve implementation
            - Fix issue
            - Refactor code
            - Various changes
            Describe the actual technical or functional change.
            Mention the affected behavior, component, API, mapping, data structure, configuration, validation, persistence, or error handling where relevant.
            Prefer precise verbs such as Add, Remove, Validate, Map, Persist, Expose, Handle, Rename, Replace, Restrict, Synchronize.
            Do not invent business context that is not visible in the diff.
            Keep the subject at most 72 characters and do not end it with a period.
            Return only the commit message without quotation marks, markdown, explanation, branch name, or ticket identifier.
            Do not inspect the repository and do not run any commands; use only this diff.

            DIFF START
            $diff
            DIFF END
        """.trimIndent()
    }

    private fun stripConventionalCommitPrefix(message: String): String =
        message
            .replace(Regex("""^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([^)]+\))?!?:\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
}
