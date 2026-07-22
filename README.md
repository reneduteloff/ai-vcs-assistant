# AI VCS Assistant for IntelliJ IDEA

Local IntelliJ plugin for generating a reviewable Git commit message with an existing local AI CLI login.

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

When the Commit, Pull Requests, or Merge Requests tool window is visible, the right-side **AI VCS Assistant** tool window is available. Use it to generate a commit message into the Commit tool window, or to generate pull request title and description into the visible pull request form.

In settings, choose the AI provider under **AI Provider**. Built-in presets are available for Codex (`codex`), Antigravity (`agy`), GitHub Copilot (`copilot`), and Claude (`claude`); use **Custom** for another CLI by configuring the executable, arguments, output mode, and prompt delivery.
