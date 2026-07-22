package com.aivcsassistant

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

class GeneratePullRequestContentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        generate(project, currentUiRoot(e))
    }

    fun generate(project: Project, pullRequestUiRoot: Component?) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            AiVcsAssistantSupport.notify(project, "Could not determine the project directory.", NotificationType.WARNING)
            return
        }

        val repositoryRoot = try {
            AiVcsAssistantSupport.gitRepositoryRoot(File(basePath))
        } catch (ex: Exception) {
            AiVcsAssistantSupport.notify(project, ex.message ?: ex.javaClass.simpleName, NotificationType.ERROR)
            return
        }

        val currentBranch = try {
            AiVcsAssistantSupport.currentBranch(repositoryRoot)
        } catch (ex: Exception) {
            AiVcsAssistantSupport.notify(project, ex.message ?: ex.javaClass.simpleName, NotificationType.ERROR)
            return
        }

        val targetBranch = AiVcsAssistantSupport.suggestedPullRequestTargetBranch(
            repositoryRoot,
            selectedPullRequestTargetBranch(pullRequestUiRoot, currentBranch)
                ?: AiVcsAssistantSettings.getInstance().state.defaultPullRequestTargetBranch,
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Pull Request Content",
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading branch diff"
                    val diff = AiVcsAssistantSupport.createPullRequestDiff(repositoryRoot, targetBranch)
                    if (diff.isBlank()) {
                        throw IllegalStateException("Git returned an empty diff for $targetBranch...HEAD.")
                    }

                    indicator.checkCanceled()
                    indicator.text = "Asking ${AiVcsAssistantSupport.currentProviderName()} for pull request content"
                    val settings = AiVcsAssistantSettings.getInstance().state
                    val raw = AiVcsAssistantSupport.runProvider(repositoryRoot, buildPrompt(diff), indicator)
                    val parsed = PullRequestJsonParser.parse(AiVcsAssistantSupport.sanitize(raw))

                    val branchIdentifier = AiVcsAssistantSupport.extractBranchIdentifier(
                        currentBranch,
                        settings.branchIdentifierRegex,
                    )
                    val subject = AiVcsAssistantSupport.applyTemplate(
                        settings.pullRequestSubjectTemplate,
                        mapOf(
                            "branch" to branchIdentifier,
                            "title" to parsed.title,
                            "provider" to AiVcsAssistantSupport.currentProviderName(settings),
                        ),
                        parsed.title,
                    )
                    val descriptionTemplate = pullRequestDescriptionTemplate(repositoryRoot, settings)
                    val description = AiVcsAssistantSupport.applyPullRequestDescriptionTemplate(
                        descriptionTemplate,
                        mapOf(
                            "branch" to branchIdentifier,
                            "title" to parsed.title,
                            "summary" to parsed.summary,
                            "changes" to AiVcsAssistantSupport.formatPullRequestList(parsed.changes),
                            "testing" to AiVcsAssistantSupport.formatPullRequestList(parsed.testing),
                            "provider" to AiVcsAssistantSupport.currentProviderName(settings),
                        ),
                        parsed.summary,
                    )

                    ApplicationManager.getApplication().invokeLater {
                        if (fillPullRequestFields(pullRequestUiRoot, subject, description)) {
                            AiVcsAssistantSupport.notify(
                                project,
                                "Pull request content generated. Review or edit it before creating the pull request.",
                                NotificationType.INFORMATION,
                            )
                        } else {
                            PullRequestContentDialog(project, subject, description).show()
                        }
                    }
                } catch (ex: AiVcsAssistantSupport.ProviderLoginRequiredException) {
                    AiVcsAssistantSupport.notifyLoginRequired(project, ex, repositoryRoot)
                } catch (ex: AiVcsAssistantSupport.ProviderSetupRequiredException) {
                    AiVcsAssistantSupport.notifySetupRequired(project, ex, repositoryRoot)
                } catch (ex: PullRequestJsonParser.ParseException) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Could not parse provider JSON output.\n\n${ex.message}\n\nRaw output:\n${ex.raw.take(4_000)}",
                            "Pull Request Content Generation Failed",
                        )
                    }
                } catch (ex: Exception) {
                    AiVcsAssistantSupport.notify(project, ex.message ?: ex.javaClass.simpleName, NotificationType.ERROR)
                }
            }
        })
    }

    private fun buildPrompt(diff: String): String =
        """
            Generate pull request content from the branch diff below.
            Describe only changes visible in the diff.
            Be specific and non-generic.
            Do not invent tests; state clearly when no test changes are visible.
            Avoid marketing language.
            Avoid repetition between title, summary, and change list.
            Keep the title concise.
            Do not include a branch identifier, ticket identifier, markdown, or commentary outside JSON.
            Return exactly one JSON object in this shape:
            {
              "title": "Add partner cancellation endpoint",
              "summary": "Adds support for cancelling a service or fuel card associated with a contract.",
              "changes": [
                "Adds request and response mappings",
                "Handles downstream service errors",
                "Adds unit tests"
              ],
              "testing": [
                "Unit tests added"
              ]
            }

            DIFF START
            $diff
            DIFF END
        """.trimIndent()

    private fun pullRequestDescriptionTemplate(repositoryRoot: Path, settings: AiVcsAssistantSettings.State): String {
        val repositoryTemplate = repositoryRoot.resolve(".github").resolve("pull_request_template.md")
        if (Files.isRegularFile(repositoryTemplate)) {
            return Files.readString(repositoryTemplate, StandardCharsets.UTF_8)
        }
        return settings.pullRequestDescriptionTemplate.ifBlank { DEFAULT_PULL_REQUEST_DESCRIPTION_TEMPLATE }
    }

    private fun selectedPullRequestTargetBranch(root: Component?, currentBranch: String): String? {
        return components(root)
            .filterIsInstance<JComboBox<*>>()
            .mapNotNull { comboBox -> comboBox.selectedItem?.toString() }
            .flatMap(::branchCandidates)
            .firstOrNull { candidate -> candidate != currentBranch && candidate != "HEAD" }
    }

    private fun currentUiRoot(e: AnActionEvent): Component? {
        val contextComponent = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return nearestPullRequestFormRoot(contextComponent)
            ?: nearestPullRequestFormRoot(focused)
    }

    private fun fillPullRequestFields(root: Component?, subject: String, description: String): Boolean {
        val fields = editableTextComponents(root)
            .filter { it.isShowing && it.isEnabled }
            .distinct()

        if (fields.size !in 2..12) return false

        val descriptionField = fields
            .filter { it is JTextArea || it.height >= 80 || it.text.contains('\n') }
            .maxByOrNull { it.height * it.width }

        val subjectField = fields
            .filter { it !== descriptionField }
            .filter { it.height <= 60 || !it.text.contains('\n') }
            .minByOrNull { it.text.length }

        if (subjectField == null || descriptionField == null) return false

        subjectField.text = subject
        descriptionField.text = description
        subjectField.caretPosition = subjectField.text.length
        descriptionField.caretPosition = 0
        return true
    }

    private fun nearestPullRequestFormRoot(component: Component?): Component? {
        var current = component
        val window = component?.let { SwingUtilities.getWindowAncestor(it) }
        while (current != null && current !== window && current !is JRootPane) {
            if (isLikelyPullRequestFormRoot(current)) return current
            current = current.parent
        }
        return null
    }

    private fun isLikelyPullRequestFormRoot(component: Component): Boolean {
        if (!component.isShowing) return false
        val fields = editableTextComponents(component)
            .filter { it.isShowing && it.isEnabled }
            .distinct()
        if (fields.size !in 2..12) return false

        val hasSubjectField = fields.any { field -> field !is JTextArea && field.height <= 60 && !field.text.contains('\n') }
        val hasDescriptionField = fields.any { field -> field is JTextArea || field.height >= 80 || field.text.contains('\n') }
        return hasSubjectField && hasDescriptionField
    }

    private fun editableTextComponents(component: Component?): List<JTextComponent> {
        return components(component).filterIsInstance<JTextComponent>().filter { it.isEditable }
    }

    private fun components(component: Component?): List<Component> {
        if (component == null) return emptyList()
        val result = mutableListOf<Component>()
        fun collect(current: Component) {
            result.add(current)
            if (current is Container) {
                current.components.forEach(::collect)
            }
        }
        collect(component)
        return result
    }

    private fun branchCandidates(value: String): List<String> =
        value
            .replace(Regex("""<[^>]+>"""), " ")
            .split(Regex("""\s+|[→←]"""))
            .map { it.trim().removePrefix("refs/heads/").removePrefix("refs/remotes/") }
            .filter { it.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._/-]*""")) }
}

private class PullRequestContentDialog(
    project: Project,
    subject: String,
    description: String,
) : DialogWrapper(project) {
    private val subjectField = JBTextField(subject)
    private val descriptionArea = JBTextArea(description, 16, 72).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Pull Request Content"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val subjectPanel = JPanel(BorderLayout(8, 0)).apply {
            add(subjectField, BorderLayout.CENTER)
            add(copyButton { subjectField.text }, BorderLayout.EAST)
        }
        val descriptionPanel = JPanel(BorderLayout(8, 0)).apply {
            add(JBScrollPane(descriptionArea).apply { preferredSize = Dimension(720, 360) }, BorderLayout.CENTER)
            add(copyButton { descriptionArea.text }, BorderLayout.EAST)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Pull request subject:"), subjectPanel, 1, false)
            .addLabeledComponent(JBLabel("Pull request description:"), descriptionPanel, 1, false)
            .panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun copyButton(text: () -> String): JButton =
        JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(text()))
            }
        }
}

data class PullRequestContent(
    val title: String,
    val summary: String,
    val changes: List<String>,
    val testing: List<String>,
)

object PullRequestJsonParser {
    class ParseException(message: String, val raw: String) : Exception(message)

    fun parse(raw: String): PullRequestContent {
        val parser = Parser(raw)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) throw ParseException("Unexpected content after JSON object.", raw)
        val obj = value as? Map<*, *> ?: throw ParseException("Expected a JSON object.", raw)

        fun stringField(name: String): String {
            val value = obj[name] as? String ?: throw ParseException("Missing or invalid '$name' string.", raw)
            return value.trim().ifBlank { throw ParseException("Field '$name' is blank.", raw) }
        }

        fun stringListField(name: String): List<String> {
            val value = obj[name] as? List<*> ?: throw ParseException("Missing or invalid '$name' array.", raw)
            return value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotBlank) }
        }

        return PullRequestContent(
            title = stringField("title"),
            summary = stringField("summary"),
            changes = stringListField("changes"),
            testing = stringListField("testing"),
        )
    }

    private class Parser(private val raw: String) {
        private var index = 0

        fun isAtEnd(): Boolean = index >= raw.length

        fun skipWhitespace() {
            while (!isAtEnd() && raw[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (isAtEnd()) throw ParseException("Unexpected end of JSON.", raw)
            return when (raw[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (raw[index] == '-' || raw[index].isDigit()) {
                        parseNumber()
                    } else {
                        throw ParseException("Unexpected JSON token '${raw[index]}'.", raw)
                    }
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("Expected ',' or '}' in JSON object.", raw)
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("Expected ',' or ']' in JSON array.", raw)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (!isAtEnd()) {
                val char = raw[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> result.append(char)
                }
            }
            throw ParseException("Unterminated JSON string.", raw)
        }

        private fun parseEscape(): Char {
            if (isAtEnd()) throw ParseException("Unterminated JSON escape.", raw)
            return when (val escaped = raw[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> throw ParseException("Invalid JSON escape '\\$escaped'.", raw)
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > raw.length) throw ParseException("Invalid unicode escape.", raw)
            val hex = raw.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar() ?: throw ParseException("Invalid unicode escape '$hex'.", raw)
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!raw.startsWith(expected, index)) {
                throw ParseException("Invalid JSON literal.", raw)
            }
            index += expected.length
            return value
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek('-')) index++
            while (!isAtEnd() && raw[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (!isAtEnd() && raw[index].isDigit()) index++
            }
            if (!isAtEnd() && (raw[index] == 'e' || raw[index] == 'E')) {
                index++
                if (!isAtEnd() && (raw[index] == '+' || raw[index] == '-')) index++
                while (!isAtEnd() && raw[index].isDigit()) index++
            }
            return raw.substring(start, index).toDoubleOrNull()
                ?: throw ParseException("Invalid JSON number.", raw)
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (isAtEnd() || raw[index] != expected) {
                throw ParseException("Expected '$expected'.", raw)
            }
            index++
        }

        private fun peek(expected: Char): Boolean = !isAtEnd() && raw[index] == expected
    }
}
