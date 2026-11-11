# AI VCS Assistant for IntelliJ IDEA

Local IntelliJ plugin for generating a reviewable Git commit message with an existing local AI CLI login.

## Compatibility

- IntelliJ IDEA Ultimate 2025.3 or newer
- Minimum IDE build: 253
- No maximum build: the plugin declares no `until-build`, so future IDE builds are not rejected at install time
- Java 17
- Kotlin compiler 2.4

IntelliJ enforces this during local plugin installation via the generated `since-build` metadata. Older IDE builds will reject the ZIP before installation. Because there is no `until-build` upper bound, newer builds (for example 263, 264, ...) will keep loading the plugin; if a future IDE removes an API the plugin relies on, it will fail at runtime instead of being blocked up front.

## Build

Open as a Gradle project and run `Gradle > Tasks > intellij platform > buildPlugin`.
The installable archive is written to `build/distributions/`.

## Usage

Open the Commit tool window, select changes, place the cursor in the commit-message field, and click **Generate Commit Message**. The plugin fills the field only.

When the Commit, Pull Requests, or Merge Requests tool window is visible, the right-side **AI VCS Assistant** tool window is available. Use it to generate a commit message into the Commit tool window, or to generate pull request title and description into the visible pull request form.

In settings, choose the AI provider under **AI Provider**. Built-in presets are available for Codex (`codex`), Antigravity (`agy`), GitHub Copilot (`copilot`), Claude (`claude`), Cursor (`cursor-agent`), OpenCode (`opencode`), and Kiro (`kiro-cli`); use **Custom** for another CLI by configuring the executable, arguments, output mode, and prompt delivery.

When **OpenCode** is selected, the **OpenCode model** list shows the models reported by `opencode models` across all configured providers. Pick one to pass it explicitly as `opencode run --model provider/model`, or keep `(OpenCode default)` to let OpenCode choose. Use **Refresh models** to reload the list; you can also type a model manually, optionally with a variant such as `provider/model#variant`.

When **Codex** is selected, the **Codex model** list shows the models reported by `codex debug models`. Pick one to pass it explicitly as `codex exec -m <model>`, or keep `(Codex default)` to use the model from your Codex configuration. Use **Refresh models** to reload the list; you can also type a model slug manually. Some models require a plan that supports them, and Codex reports an unsupported model as an actionable error when that happens.
