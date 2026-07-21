# AI VCS Assistant for IntelliJ IDEA

Local IntelliJ plugin for generating a reviewable Git commit message and editable pull request content with an existing local AI CLI login.

## Compatibility

- IntelliJ IDEA Ultimate 2025.3 or newer
- Minimum IDE build: 253
- Tested target range in plugin metadata: 253 through 262.*
- Java 17
- Kotlin compiler 2.4

IntelliJ enforces this during local plugin installation via the generated `since-build` metadata. Older IDE builds will reject the ZIP before installation.

## Build

Open as a Gradle project and run `Gradle > Tasks > intellij platform > buildPlugin`.
The installable archive is written to `build/distributions/`.

## Usage

Open the Commit tool window, select changes, place the cursor in the commit-message field, and click **Generate Commit Message**. The plugin fills the field only.

Use **Generate Pull Request Content** to choose a target branch and generate an editable pull request subject and description from `git diff <target-branch>...HEAD`. The plugin never commits, pushes, or creates pull requests.

In settings, choose the AI provider under **AI Provider**. Built-in presets are available for Codex (`codex`), Antigravity (`agy`), GitHub Copilot (`copilot`), and Claude (`claude`); use **Custom** for another CLI by configuring the executable, arguments, output mode, and prompt delivery.
