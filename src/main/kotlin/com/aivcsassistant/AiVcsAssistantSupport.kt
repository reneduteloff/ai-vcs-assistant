package com.aivcsassistant

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object AiVcsAssistantSupport {
    const val PROVIDER_CODEX = "Codex"
    const val PROVIDER_ANTIGRAVITY = "Antigravity"
    const val PROVIDER_GITHUB_COPILOT = "GitHub Copilot"
    const val PROVIDER_CLAUDE = "Claude"
    const val PROVIDER_CURSOR = "Cursor"
    const val PROVIDER_CUSTOM = "Custom"

    fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI VCS Assistant")
            .createNotification(content, type)
            .notify(project)
    }

    fun notifyLoginRequired(project: Project, error: ProviderLoginRequiredException, repositoryRoot: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI VCS Assistant")
            .createNotification(
                "${error.providerName} requires login. Open a terminal and finish authentication, then retry generation.",
                error.details.take(1_000),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Open login terminal") {
                    openLoginTerminal(project, repositoryRoot, error.loginCommand)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Copy login command") {
                    copyToClipboard(project, shellCommand(error.loginCommand), "Login command copied.")
                },
            )
            .notify(project)
    }

    fun notifySetupRequired(project: Project, error: ProviderSetupRequiredException, repositoryRoot: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI VCS Assistant")
            .createNotification(
                "${error.providerName} CLI setup is required. Open a terminal for install/update commands, then retry generation.",
                error.details.take(1_000),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Open setup terminal") {
                    openSetupTerminal(project, repositoryRoot, error.setupScript)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Copy setup commands") {
                    copyToClipboard(project, error.setupScript, "Setup commands copied.")
                },
            )
            .notify(project)
    }

    fun gitRepositoryRoot(startDirectory: File): Path {
        val process = ProcessBuilder("git", "-C", startDirectory.absolutePath, "rev-parse", "--show-toplevel")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Timed out while determining the Git repository.")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("Could not determine the Git repository: ${output.trim()}")
        }
        return Path.of(output.trim()).toAbsolutePath().normalize()
    }

    fun currentBranch(repositoryRoot: Path): String {
        val output = runGit(repositoryRoot, listOf("rev-parse", "--abbrev-ref", "HEAD"), 15)
        return output.trim()
    }

    fun createSelectedChangesDiff(repositoryRoot: Path, paths: List<String>): String {
        val command = mutableListOf("diff", "HEAD", "--no-ext-diff", "--unified=3", "--")
        command.addAll(paths)
        return runGit(repositoryRoot, command, 30)
    }

    fun createPullRequestDiff(repositoryRoot: Path, targetBranch: String): String {
        if (!gitRefExists(repositoryRoot, targetBranch)) {
            throw IllegalStateException(
                "Target branch/ref '$targetBranch' does not exist in this repository. Select an existing local or remote branch, for example ${suggestedPullRequestTargetBranch(repositoryRoot, targetBranch)}.",
            )
        }
        return runGit(repositoryRoot, listOf("diff", "--no-ext-diff", "--unified=3", "$targetBranch...HEAD", "--"), 45)
    }

    fun suggestedPullRequestTargetBranch(repositoryRoot: Path, configuredTargetBranch: String): String {
        val configured = configuredTargetBranch.trim()
        if (configured.isNotBlank()) {
            targetRefCandidates(configured).firstOrNull { gitRefExists(repositoryRoot, it) }?.let { return it }
        }

        defaultRemoteBranch(repositoryRoot)?.let { return it }

        return listOf("origin/main", "main", "origin/master", "master", "origin/develop", "develop")
            .firstOrNull { gitRefExists(repositoryRoot, it) }
            ?: configured.ifBlank { "main" }
    }

    fun currentProviderName(settings: AiVcsAssistantSettings.State = AiVcsAssistantSettings.getInstance().state): String =
        when (settings.provider) {
            PROVIDER_CODEX -> PROVIDER_CODEX
            PROVIDER_ANTIGRAVITY -> PROVIDER_ANTIGRAVITY
            PROVIDER_GITHUB_COPILOT -> PROVIDER_GITHUB_COPILOT
            PROVIDER_CLAUDE -> PROVIDER_CLAUDE
            PROVIDER_CURSOR -> PROVIDER_CURSOR
            PROVIDER_CUSTOM -> settings.customProviderName.ifBlank { PROVIDER_CUSTOM }
            else -> settings.provider.ifBlank { PROVIDER_CODEX }
        }

    fun runProvider(repositoryRoot: Path, prompt: String, indicator: ProgressIndicator): String {
        val settings = AiVcsAssistantSettings.getInstance().state
        val provider = providerCommand(settings)
        val outputFile = Files.createTempFile("ai-vcs-assistant-output-", ".txt")
        val promptFile = Files.createTempFile("ai-vcs-assistant-prompt-", ".txt")
        try {
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8)
            val executable = resolveExecutable(provider.executable)
            val command = listOf(executable.toString()) + expandArguments(
                provider.arguments,
                mapOf(
                    "outputFile" to outputFile.toString(),
                    "promptFile" to promptFile.toString(),
                    "prompt" to prompt,
                    "timeoutSeconds" to settings.timeoutSeconds.toString(),
                ),
            )
            val process = ProcessBuilder(command)
                .directory(repositoryRoot.toFile())
                .apply { augmentPath(environment(), executable) }
                .start()

            if (provider.promptViaStdin) {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(prompt)
                }
            } else {
                process.outputStream.close()
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (stdout.length < 32_000) stdout.append(line).append('\n')
                    }
                }
            }.apply {
                name = "${provider.name} stdout reader"
                isDaemon = true
                start()
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (stderr.length < 16_000) stderr.append(line).append('\n')
                    }
                }
            }.apply {
                name = "${provider.name} stderr reader"
                isDaemon = true
                start()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.timeoutSeconds.toLong())
            while (process.isAlive && System.nanoTime() < deadline) {
                if (indicator.isCanceled) {
                    process.destroyForcibly()
                    throw IllegalStateException("Generation was cancelled.")
                }
                Thread.sleep(150)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                throw IllegalStateException("${provider.name} did not finish within ${settings.timeoutSeconds} seconds.")
            }
            stdoutThread.join(2_000)
            stderrThread.join(2_000)

            if (process.exitValue() != 0) {
                val errorOutput = (stderr.toString() + stdout.toString()).trim()
                if (looksLikeLoginRequired(errorOutput)) {
                    throw ProviderLoginRequiredException(provider.name, providerLoginCommand(provider, executable), errorOutput)
                }
                if (looksLikeSetupRequired(provider, errorOutput)) {
                    throw ProviderSetupRequiredException(provider.name, providerSetupScript(provider), errorOutput)
                }
                throw IllegalStateException(
                    "${provider.name} CLI failed with exit code ${process.exitValue()}: $errorOutput",
                )
            }
            val output = if (provider.readOutputFromFile) {
                Files.readString(outputFile, StandardCharsets.UTF_8)
            } else {
                stdout.toString()
            }
            if (looksLikeSetupRequired(provider, output)) {
                throw ProviderSetupRequiredException(provider.name, providerSetupScript(provider), output.trim())
            }
            return output
        } catch (ex: java.io.IOException) {
            if (looksLikeMissingExecutable(ex)) {
                throw ProviderSetupRequiredException(
                    provider.name,
                    providerSetupScript(provider),
                    "Could not start '${provider.executable}': ${ex.message}",
                )
            } else {
                throw IllegalStateException(
                    "Could not start '${provider.executable}'. Configure the provider under Settings | AI VCS Assistant. ${ex.message}",
                    ex,
                )
            }
        } finally {
            Files.deleteIfExists(outputFile)
            Files.deleteIfExists(promptFile)
        }
    }

    fun sanitize(raw: String): String = raw
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
        .removeSurrounding("\"")

    fun extractBranchIdentifier(branchName: String, regex: String): String {
        if (branchName.isBlank() || regex.isBlank()) return ""
        return runCatching { Regex(regex).find(branchName)?.value.orEmpty() }.getOrElse {
            throw IllegalStateException("Invalid branch identifier regular expression: ${it.message}")
        }
    }

    fun applyTemplate(
        template: String,
        values: Map<String, String>,
        fallback: String,
        preserveLineBreaks: Boolean = false,
    ): String {
        var result = template
        values.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        val cleaned = if (preserveLineBreaks) cleanMultilineTemplateResult(result) else cleanTemplateResult(result)
        return cleaned.ifBlank { fallback.trim() }
    }

    fun formatPullRequestList(items: List<String>): String =
        if (items.isEmpty()) "- No visible changes" else items.joinToString("\n") { "- $it" }

    fun applyPullRequestDescriptionTemplate(
        template: String,
        values: Map<String, String>,
        fallback: String,
    ): String {
        val placeholderPattern = Regex("""\{(?:branch|title|summary|changes|testing|provider)}""")
        if (placeholderPattern.containsMatchIn(template)) {
            return applyTemplate(template, values, fallback, preserveLineBreaks = true)
        }

        var result = template.trim()
        result = replaceSection(
            result,
            Regex("""(?im)^##\s*(?:📌\s*)?Summary\s*$"""),
            listOfNotNull(
                values["summary"]?.takeIf(String::isNotBlank),
                values["changes"]?.takeIf(String::isNotBlank)?.let { "### Changes\n$it" },
            ).joinToString("\n\n"),
        )
        result = replaceSection(
            result,
            Regex("""(?im)^##\s*(?:🧪\s*)?Testing Steps\s*$"""),
            formatNumberedPullRequestList(values["testing"].orEmpty().lines().map { it.removePrefix("- ").trim() }.filter(String::isNotBlank)),
        )
        return result.trim().ifBlank { fallback.trim() }
    }

    private fun cleanTemplateResult(value: String): String {
        var result = value.trim()
        result = result.replace(Regex("""\s+"""), " ")
        result = result.replace(Regex("""^[\s:;,\-|/\\\[\](){}]+"""), "")
        result = result.replace(Regex("""[\s:;,\-|/\\\[\](){}]+$"""), "")
        result = result.replace(Regex("""\[\s*]|\(\s*\)|\{\s*}"""), "")
        result = result.replace(Regex("""\s*[:;,\-|/\\]\s*[:;,\-|/\\]\s*"""), ": ")
        result = result.replace(Regex("""^\s*[:;,\-|/\\]\s*"""), "")
        result = result.replace(Regex("""\s*[:;,\-|/\\]\s*$"""), "")
        return result.replace(Regex("""\s+"""), " ").trim()
    }

    private fun cleanMultilineTemplateResult(value: String): String {
        val separatorOnly = Regex("""^[\s:;,\-|/\\\[\](){}]+$""")
        return value
            .lines()
            .map { line ->
                val trimmed = line.trim()
                if (separatorOnly.matches(trimmed)) "" else line.trimEnd()
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun formatNumberedPullRequestList(items: List<String>): String =
        if (items.isEmpty()) {
            "1. No test changes are visible in the diff."
        } else {
            items.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
        }

    private fun replaceSection(template: String, headingPattern: Regex, body: String): String {
        val match = headingPattern.find(template) ?: return template
        val nextHeading = Regex("""(?m)^##\s+""").find(template, match.range.last + 1)
        val sectionEnd = nextHeading?.range?.first ?: template.length
        return template.substring(0, match.range.last + 1) +
            "\n\n" +
            body.trim() +
            "\n\n" +
            template.substring(sectionEnd).trimStart()
    }

    private fun runGit(repositoryRoot: Path, args: List<String>, timeoutSeconds: Long): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Timed out while running git ${args.joinToString(" ")}.")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("git ${args.joinToString(" ")} failed: ${output.trim()}")
        }
        return output
    }

    private fun gitRefExists(repositoryRoot: Path, ref: String): Boolean =
        ref.isNotBlank() && runCatching {
            runGit(repositoryRoot, listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"), 10)
        }.isSuccess

    private fun defaultRemoteBranch(repositoryRoot: Path): String? =
        runCatching {
            runGit(repositoryRoot, listOf("symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"), 10).trim()
        }.getOrNull()?.takeIf(String::isNotBlank)

    private fun targetRefCandidates(targetBranch: String): List<String> =
        if ('/' in targetBranch) listOf(targetBranch) else listOf(targetBranch, "origin/$targetBranch")

    private data class ProviderCommand(
        val name: String,
        val executable: String,
        val arguments: String,
        val readOutputFromFile: Boolean,
        val promptViaStdin: Boolean,
    )

    class ProviderLoginRequiredException(
        val providerName: String,
        val loginCommand: List<String>,
        val details: String,
    ) : IllegalStateException("$providerName requires login.")

    class ProviderSetupRequiredException(
        val providerName: String,
        val setupScript: String,
        val details: String,
    ) : IllegalStateException("$providerName CLI setup is required.")

    private fun providerCommand(settings: AiVcsAssistantSettings.State): ProviderCommand =
        when (settings.provider) {
            PROVIDER_ANTIGRAVITY -> ProviderCommand(
                name = PROVIDER_ANTIGRAVITY,
                executable = settings.antigravityExecutable.ifBlank { "agy" },
                arguments = "--print {prompt} --print-timeout {timeoutSeconds}s",
                readOutputFromFile = false,
                promptViaStdin = false,
            )
            PROVIDER_GITHUB_COPILOT -> ProviderCommand(
                name = PROVIDER_GITHUB_COPILOT,
                executable = settings.githubCopilotExecutable.ifBlank { "copilot" },
                arguments = "-sp {prompt}",
                readOutputFromFile = false,
                promptViaStdin = false,
            )
            PROVIDER_CLAUDE -> ProviderCommand(
                name = PROVIDER_CLAUDE,
                executable = settings.claudeExecutable.ifBlank { "claude" },
                arguments = "-p {prompt}",
                readOutputFromFile = false,
                promptViaStdin = false,
            )
            PROVIDER_CURSOR -> ProviderCommand(
                name = PROVIDER_CURSOR,
                executable = settings.cursorExecutable.ifBlank { "cursor-agent" },
                arguments = "-p --output-format text {prompt}",
                readOutputFromFile = false,
                promptViaStdin = false,
            )
            PROVIDER_CUSTOM -> ProviderCommand(
                name = settings.customProviderName.ifBlank { PROVIDER_CUSTOM },
                executable = settings.customExecutable.ifBlank {
                    throw IllegalStateException("Configure a custom provider executable.")
                },
                arguments = settings.customArguments,
                readOutputFromFile = settings.customReadOutputFromFile,
                promptViaStdin = settings.customPromptViaStdin,
            )
            else -> ProviderCommand(
                name = PROVIDER_CODEX,
                executable = settings.codexExecutable.ifBlank { "codex" },
                arguments = "exec --sandbox read-only --ephemeral -c approval_policy=\\\"never\\\" --output-last-message {outputFile} -",
                readOutputFromFile = true,
                promptViaStdin = true,
            )
        }

    private fun expandArguments(arguments: String, values: Map<String, String>): List<String> =
        splitCommandLine(arguments).map { argument ->
            values.entries.fold(argument) { result, (key, value) ->
                result.replace("{$key}", value)
            }
        }.filter(String::isNotBlank)

    private fun looksLikeLoginRequired(output: String): Boolean {
        val normalized = output.lowercase()
        return listOf(
            "not logged in",
            "not authenticated",
            "authentication required",
            "login required",
            "please login",
            "please log in",
            "sign in",
            "sign-in",
            "not signed in",
            "api key",
            "invalid api key",
            "no api key",
            "unauthorized",
            "requires authentication",
        ).any(normalized::contains)
    }

    private fun looksLikeMissingExecutable(ex: java.io.IOException): Boolean =
        ex.message?.contains("No such file or directory", ignoreCase = true) == true ||
            ex.message?.contains("error=2", ignoreCase = true) == true

    private fun looksLikeSetupRequired(provider: ProviderCommand, output: String): Boolean {
        val normalized = output.lowercase()
        return when (provider.name) {
            PROVIDER_GITHUB_COPILOT -> "gh-copilot extension has been deprecated" in normalized ||
                "deprecated in favor of the newer github copilot cli" in normalized
            PROVIDER_CURSOR -> "command not found: cursor-agent" in normalized ||
                "cursor-agent: command not found" in normalized
            else -> false
        }
    }

    private fun providerLoginCommand(provider: ProviderCommand, executable: Path): List<String> =
        when (provider.name) {
            PROVIDER_CODEX -> listOf(executable.toString(), "login")
            PROVIDER_ANTIGRAVITY -> listOf(executable.toString())
            PROVIDER_GITHUB_COPILOT -> listOf(executable.toString(), "login")
            PROVIDER_CLAUDE -> listOf(executable.toString())
            PROVIDER_CURSOR -> listOf(executable.toString(), "login")
            else -> listOf(executable.toString())
        }

    private fun openLoginTerminal(project: Project, repositoryRoot: Path, loginCommand: List<String>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                TerminalToolWindowTabsManager.getInstance(project)
                    .createTabBuilder()
                    .workingDirectory(repositoryRoot.toString())
                    .tabName("AI Provider Login")
                    .requestFocus(true)
                    .shellCommand(loginCommand)
                    .createTab()
            } catch (ex: Exception) {
                notify(project, "Could not open login terminal: ${ex.message ?: ex.javaClass.simpleName}", NotificationType.ERROR)
            }
        }
    }

    private fun openSetupTerminal(project: Project, repositoryRoot: Path, setupScript: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                TerminalToolWindowTabsManager.getInstance(project)
                    .createTabBuilder()
                    .workingDirectory(repositoryRoot.toString())
                    .tabName("AI Provider Setup")
                    .requestFocus(true)
                    .shellCommand(listOf("bash", "-lc", setupScript))
                    .createTab()
            } catch (ex: Exception) {
                notify(project, "Could not open setup terminal: ${ex.message ?: ex.javaClass.simpleName}", NotificationType.ERROR)
            }
        }
    }

    private fun copyToClipboard(project: Project, text: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            notify(project, message, NotificationType.INFORMATION)
        }
    }

    private fun providerSetupScript(provider: ProviderCommand): String =
        when (provider.name) {
            PROVIDER_GITHUB_COPILOT -> setupScript(
                "GitHub Copilot CLI setup",
                listOf(
                    "Install the current GitHub Copilot CLI, then run copilot login.",
                    "Recommended npm installer, requires Node.js 22 or later:",
                    "npm install -g @github/copilot",
                    "Alternative macOS/Linux installer:",
                    "curl -fsSL https://gh.io/copilot-install | bash",
                    "If gh copilot still shows the deprecated extension, remove that old extension separately:",
                    "gh extension remove copilot",
                ),
                emptyList(),
            )
            PROVIDER_CLAUDE -> setupScript(
                "Claude Code CLI setup",
                listOf(
                    "Install Claude Code, then run claude and finish login.",
                    "Recommended macOS/Linux/WSL installer:",
                    "curl -fsSL https://claude.ai/install.sh | bash",
                    "Alternative npm installer:",
                    "npm install -g @anthropic-ai/claude-code",
                ),
                emptyList(),
            )
            PROVIDER_CURSOR -> setupScript(
                "Cursor CLI setup",
                listOf(
                    "Install Cursor CLI, then run cursor-agent login.",
                    "macOS/Linux installer:",
                    "curl https://cursor.com/install -fsS | bash",
                ),
                emptyList(),
            )
            PROVIDER_CODEX -> setupScript(
                "Codex CLI setup",
                listOf(
                    "Install Codex CLI, then run codex login.",
                    "Common npm installer:",
                    "npm install -g @openai/codex",
                ),
                emptyList(),
            )
            PROVIDER_ANTIGRAVITY -> setupScript(
                "Antigravity CLI setup",
                listOf(
                    "Install Antigravity CLI and ensure agy is available in PATH.",
                    "After installation, run agy and finish login/setup.",
                ),
                emptyList(),
            )
            else -> setupScript(
                "${provider.name} CLI setup",
                listOf("Install ${provider.executable} and ensure it is available in PATH, or configure an absolute executable path in Settings | AI VCS Assistant."),
                emptyList(),
            )
        }

    private fun setupScript(title: String, lines: List<String>, commandsToRun: List<String>): String {
        val message = (listOf(title, "") + lines + listOf("", "Retry generation after setup is complete."))
            .joinToString("\n")
            .replace("'", "'\"'\"'")
        val commandBlock = commandsToRun.joinToString("\n")
        val commandText = if (commandBlock.isBlank()) "" else "\n$commandBlock"
        return "printf '%s\\n' '$message'$commandText\nexec \${SHELL:-/bin/bash} -i"
    }

    private fun shellCommand(command: List<String>): String =
        command.joinToString(" ") { argument ->
            "'${argument.replace("'", "'\"'\"'")}'"
        }

    private fun resolveExecutable(executable: String): Path {
        val trimmed = executable.trim()
        if (trimmed.isBlank()) throw IllegalStateException("Configure a provider executable.")
        val configuredPath = Path.of(trimmed)
        if (configuredPath.isAbsolute || trimmed.contains('/') || trimmed.contains('\\')) {
            return configuredPath.toAbsolutePath().normalize()
        }

        val matches = executableSearchDirectories()
            .flatMap { directory -> executableNames(trimmed).map { directory.resolve(it) } }
            .firstOrNull(::isRunnableFile)
        return matches ?: configuredPath
    }

    private fun executableNames(executable: String): List<String> {
        val extension = executable.substringAfterLast('.', "").lowercase()
        if (File.separatorChar != '\\' || extension in setOf("exe", "cmd", "bat", "ps1")) {
            return listOf(executable)
        }
        return listOf(executable, "$executable.exe", "$executable.cmd", "$executable.bat", "$executable.ps1")
    }

    private fun isRunnableFile(path: Path): Boolean =
        Files.isRegularFile(path) && (File.separatorChar == '\\' || Files.isExecutable(path))

    private fun augmentPath(environment: MutableMap<String, String>, executable: Path) {
        val existingPath = environment["PATH"].orEmpty()
        val additions = (listOfNotNull(executable.parent) + fallbackExecutableDirectories())
            .map { it.toAbsolutePath().normalize().toString() }
            .distinct()
            .filter { existingPath.split(File.pathSeparator).none { path -> path == it } }
        if (additions.isNotEmpty()) {
            environment["PATH"] = (additions + existingPath)
                .filter(String::isNotBlank)
                .joinToString(File.pathSeparator)
        }
    }

    private fun executableSearchDirectories(): List<Path> =
        (System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map { Path.of(it) } + fallbackExecutableDirectories())
            .map { it.toAbsolutePath().normalize() }
            .distinct()

    private fun fallbackExecutableDirectories(): List<Path> {
        val home = System.getProperty("user.home")?.let { Path.of(it) } ?: return emptyList()
        val userLocalDirectories = listOf(
            home.resolve(".local/bin"),
            home.resolve("bin"),
            home.resolve(".npm-global/bin"),
            home.resolve(".volta/bin"),
            home.resolve(".asdf/shims"),
            home.resolve(".bun/bin"),
            home.resolve(".nvm/current/bin"),
        )
        val unixDirectories = if (File.separatorChar == '/') {
            listOf(
                Path.of("/opt/homebrew/bin"),
                Path.of("/usr/local/bin"),
            )
        } else {
            emptyList()
        }
        val windowsDirectories = if (File.separatorChar == '\\') {
            listOfNotNull(
                System.getenv("APPDATA")?.let { Path.of(it).resolve("npm") },
                System.getenv("LOCALAPPDATA")?.let { Path.of(it).resolve("Programs").resolve("nodejs") },
                System.getenv("LOCALAPPDATA")?.let { Path.of(it).resolve("Volta").resolve("bin") },
                System.getenv("LOCALAPPDATA")?.let { Path.of(it).resolve("Microsoft").resolve("WindowsApps") },
                System.getenv("ProgramFiles")?.let { Path.of(it).resolve("nodejs") },
                System.getenv("ProgramFiles(x86)")?.let { Path.of(it).resolve("nodejs") },
                System.getenv("ChocolateyInstall")?.let { Path.of(it).resolve("bin") },
                home.resolve("AppData").resolve("Roaming").resolve("npm"),
                home.resolve("scoop").resolve("shims"),
                Path.of("C:\\ProgramData\\chocolatey\\bin"),
            )
        } else {
            emptyList()
        }
        return userLocalDirectories + unixDirectories + windowsDirectories + nvmExecutableDirectories(home)
    }

    private fun nvmExecutableDirectories(home: Path): List<Path> {
        val nodeVersions = home.resolve(".nvm/versions/node")
        if (!Files.isDirectory(nodeVersions)) return emptyList()
        return Files.list(nodeVersions).use { versions ->
            versions
                .filter { Files.isDirectory(it.resolve("bin")) }
                .sorted { left, right -> compareNodeVersionDirectories(right, left) }
                .map { it.resolve("bin") }
                .toList()
        }
    }

    private fun compareNodeVersionDirectories(left: Path, right: Path): Int {
        val leftVersion = nodeVersionParts(left.fileName.toString())
        val rightVersion = nodeVersionParts(right.fileName.toString())
        repeat(maxOf(leftVersion.size, rightVersion.size)) { index ->
            val comparison = leftVersion.getOrElse(index) { 0 }.compareTo(rightVersion.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return left.fileName.toString().compareTo(right.fileName.toString())
    }

    private fun nodeVersionParts(versionDirectoryName: String): List<Int> =
        versionDirectoryName
            .removePrefix("v")
            .split('.')
            .mapNotNull { part -> part.toIntOrNull() }

    private fun splitCommandLine(arguments: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        arguments.forEach { char ->
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (escaping) current.append('\\')
        if (quote != null) throw IllegalStateException("Unclosed quote in provider arguments.")
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
