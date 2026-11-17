# User guide

[Home](../../README.md) · [Installation](installation.md) · [完整中文用户手册](../06_用户手册.md)

This guide covers everyday use. The Chinese manual contains the extended configuration and API reference.

## Start with a conversation or an Agent task

| Mode | Use it for |
| --- | --- |
| Conversation | Questions, attached documents and images, or supported image generation without selecting a project |
| Agent | Tasks that read, modify and run tools in a selected local or SSH project |

The two spaces share model configuration but keep their histories separately. Web, desktop and standalone CLI/TUI support personal conversations. VS Code stays in project Agent mode.

Choose a workspace before starting an Agent task. For a first task, ask Harnex to inspect the project and explain its structure. Review the selected model and permission mode before requesting file changes or command execution.

Switching a sidebar entry does not itself submit a task. **New conversation** and **New task** create new work without deleting earlier history. When handing content from a personal conversation to an Agent, select the text and assets to include and choose the target project; review the prepared draft before sending it.

## Models and settings

In **Settings → Models**, configure:

1. A provider profile matching the endpoint protocol.
2. The provider's base URL and credentials.
3. The exact model IDs supported by that endpoint.
4. The capabilities and optional pricing needed for your use.

Supported protocol families include Chat Completions, Responses, Anthropic Messages and Gemini Interactions. A protocol-compatible URL does not guarantee every model supports tools, reasoning, vision, caching or native search.

Image services are configured separately from language models. Choose a service that supports the required operation: generation, editing or inspection. Do not assume a text model endpoint also accepts image-generation requests.

Settings changes may apply to a new task or require a connection restart, as indicated in the settings interface. Running tasks use their configuration snapshot.

User YAML normally lives at `~/.harnex/agent-config.yml`. Credentials can reference environment variables. Declaring `harnex.providers` in an external YAML file replaces the provider collection, so include all providers you intend to keep. See the [built-in configuration](../../backend/src/main/resources/agent-config.yml) for current fields and defaults.

## Follow and steer a task

The conversation displays model output, tool calls, task progress and file changes. Send additional instructions while a task runs to clarify or redirect it. Use the stop/cancel control when you want execution to end.

Review requests and tool approvals appear as interactions. Respond to the requested action or choose a supplied option; a recommended option is not automatically submitted. Rejection or feedback lets the Agent revise its approach.

Call and usage views describe actual attempts, including retries, summarization and subagent work where recorded. Cost is estimated from configured prices and reported usage; it is not the provider's bill. Missing price or usage information remains unknown or appears as a known subtotal.

## Plans and Goals

Use `/plan` when you want to review a plan before implementation:

```text
/plan Refactor the settings module and preserve its current behavior
```

The Agent investigates with planning-safe tools and submits a plan for review. Approve it to proceed, or send feedback to revise it. `/plan cancel` exits planning without executing the plan. Plan approval does not expand file, command or workspace permissions.

Use `/goal` for work that needs continuing execution, progress tracking and explicit completion criteria:

```text
/goal Fix the failing tests and verify the affected workflows
```

Goals use Todo progress, checkpoints and criteria to track ongoing work. Review configured budgets before starting lengthy tasks: default Goal limits can be unlimited. A task that needs input or cannot satisfy a criterion may remain waiting or incomplete; inspect its reported state instead of assuming every stopped run succeeded.

Goals can manage their internal plans autonomously unless an existing planning gate or an explicit request requires review. Subagents remain subject to the parent task's applicable permission and budget boundaries.

## Permissions and execution

Approvals, file access and execution isolation are separate controls. Start with a permission mode appropriate for the project and review requested actions.

| Permission mode | General behavior |
| --- | --- |
| `ask` | Requests approval for actions gated by the active policy |
| `accept-edits` | Allows applicable edits while retaining approval gates for other actions |
| `yolo` | Allows actions that would otherwise ask under the normal mode policy; deny rules and outer access boundaries still apply |

Global policies and tool capabilities can further restrict these modes. A toolset controls which tools are exposed. A more permissive mode does not by itself remove outside-workspace write boundaries or provide an isolated environment.

Host execution uses the tools and permissions of the machine running the Runtime. Docker execution uses the container environment; SSH project execution uses the remote server. Install project dependencies where commands actually run.

On Linux/macOS, the shell tool uses `/bin/sh`; explicitly invoke Bash for Bash-specific syntax. Windows uses the available supported shell path, preferring Git Bash when installed.

## Files, history and attachments

Inspect diffs and tool results while reviewing Agent changes. Snapshots can restore the file changes they cover; they are not a general rollback for external services or databases.

Conversation search and history help you recover earlier decisions. Editing an earlier message or requesting a new answer preserves the appropriate history branch, but does not itself undo files on disk. Re-running an Agent task can perform new project operations.

Attach documents or images to the current input. Vision requires a model with the relevant capability. OCR can extract text but does not replace visual understanding. Supported formats and limits depend on the selected model and attachment processing.

For image creation or editing, configure an image service first. Review its operation status, accept the desired result and export it to the intended destination. Remote project operations and local personal conversations can have different file locations.

## Project context, memory and skills

Use `AGENTS.md` for project instructions: build commands, coding conventions, review expectations and important boundaries.

Harnex distinguishes task-local context from information intended for later sessions. User and project memory can retain stable preferences or knowledge; current conversation constraints and decisions belong to their relevant conversation scope.

Skills are Markdown instructions loaded when needed. Project skills live at:

```text
<project>/.harnex/skills/<skill-name>/SKILL.md
```

User skills live at `~/.harnex/skills/`. Project skills can be version-controlled; runtime data under `.harnex/data` should remain excluded. Use the skill picker or `/skill` to select an available skill.

MCP servers expose additional tools. Supported client transports include `stdio`, `sse` and `streamable-http` (`http` is an alias). Configure the server and its toolset explicitly; verify which tools are available to the selected task.

## Terminal use

```bash
# Personal conversation
harnex tui --conversation
harnex cli --conversation -p "Explain this concept"

# Project Agent
harnex tui --workspace /path/to/project
harnex cli -p "Inspect this project and describe its build process"

# Keep the terminal's main screen and scrollback
harnex tui --renderer inline
```

For one-shot project requests, run the command in the intended project or specify its workspace. Do not run project tasks inside the Runtime Bundle installation directory.

In the standalone TUI, **F6** switches between personal and project spaces. Drafts and selected conversations are retained separately. Use **Ctrl+P** for commands and settings, **Ctrl+N** for a new conversation, and **Ctrl+D** for a normal exit. Historical conversations are selected explicitly rather than automatically reopened at every launch.

The TUI supports `--lang auto|zh-CN|en`, `--theme auto|dark|light|ansi` and `--renderer fullscreen|inline`. `--model` selects an exact configured model ID; `--toolsets` selects comma-separated toolset names. Run `harnex --help` for the command's full options.

An IDE-attached terminal stays connected to its assigned Runtime. Exiting a client that borrowed an existing Runtime does not stop the owning host.

## Remote workspaces

Add an SSH connection in the GUI workspace picker, then select a project directory on a supported Linux server. The server runs its own Runtime; project files, commands, search and snapshots stay there. Remote programs and tools must be available on that server.

Use a saved connection from the terminal:

```bash
harnex tui --remote my-server --workspace /srv/my-project
```

F6 in a remote TUI switches to the local personal space while retaining the SSH project connection. `harnex cli --conversation` is local and cannot be combined with `--remote`.

Disconnecting, stopping and retaining a remote Runtime are different actions. A client attached to an existing service does not acquire ownership of that service. Check the selected lifecycle action before leaving work running remotely.

## Automation and integrations

Open **Automation** from the sidebar to create a schedule. Select a personal conversation or an available open project, set the task and schedule, then save it. A manual run can verify the configuration before enabling a recurring schedule.

Project automation uses an already open Runtime; it does not automatically open a closed project. If the target environment is unavailable or busy, work can wait. Multiple missed schedule times are coalesced rather than replayed as an immediate burst.

Pausing a schedule prevents future scheduled work and waiting requests; use the current-run cancellation control to stop an already accepted run. Inspect the saved results for completion or failure details.

IM and webhook integrations include Feishu, DingTalk, WeCom, Telegram and generic webhooks. Configure the channel credentials, authorized senders and notification destinations for the intended deployment. Notifications do not grant additional execution permissions.

## Data and troubleshooting

Personal conversations normally use `~/.harnex/personal/`; project data defaults to `<project>/.harnex/data/`. Settings diagnostics show the actual directories and Runtime identity. Follow the [backup instructions](installation.md#data-backup-and-upgrades) before replacing installations or moving data.

When work fails, start with its reported error, tool details and provider call history. Check the selected model, toolset, permission mode and execution environment. Use `harnex doctor` with the npm launcher for local prerequisites.

When reporting an issue, include the version, OS/architecture, client and a minimal reproduction. Remove credentials and private project content from logs and screenshots.
