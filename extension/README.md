# Harnex for VS Code

Thin-client VS Code extension for the Harnex Runtime. Installation and usage are described below.
The Runtime (Spring Boot) owns the agent loop, tools, sandbox and approvals; this
extension provides process management, a dedicated chat webview with host-owned state,
native diff-review approvals and permission-mode switching.

> The UI extension can own a Runtime started from the optional platform companion, or
> connect as a client to an existing Runtime for the exact same project. Discovery uses
> trusted domain manifests; arbitrary backend URLs and remote workspace switching are not supported.
> An attached VS Code client does not stop, restart or take over the host's process.

## Build & package

```bash
cd extension
npm install
npm run build          # dist/extension.js + media/chat.js (esbuild)
npx vsce package --target darwin-arm64 --allow-missing-repository # or darwin-x64 / win32-x64
```

For a self-contained offline install, install `harnex-runtime-<target>-<version>.vsix` and
the lightweight UI VSIX. The Runtime companion is optional when an existing project Runtime is available. The repository
command `npm run build:offline` creates both packages and the target installer:
`install-vscode.ps1` on Windows, `install-vscode.sh` on macOS/Linux.

## Verify (no API key needed)

```bash
cd extension
npm run typecheck
npm run build
```

Standalone test suites and smoke runners are maintained internally. Public source retains build and typecheck commands.

## Runtime ownership

`harnex.backend.mode` controls the connection:

- `auto` (default): opening or using Harnex starts a verified companion bundle when available and no manifest is configured.
  Without a bundle, the panel waits for an explicit connection action.
- `managed`: start a private Runtime from the platform companion. Explicit jar/Java paths remain development overrides.
- `existing`: wait for **连接已有 Runtime** / **Harnex: Attach to Existing Runtime**. One matching domain is used directly; multiple matches open a picker.

For example, open the project in Harnex desktop/Web, then use this VS Code setting:

```json
{ "harnex.backend.mode": "existing" }
```

Optionally set `harnex.backend.manifestPath` to that Runtime's `domain.json` (absolute or workspace-relative).
Click **连接已有 Runtime** to establish the connection. A configured manifest never initiates attach by itself.
After attaching, the first VS Code workspace folder is the project root. Native folder/workspace changes first
disconnect the editor bridge and ask whether to switch the original Tauri or Web host to that directory.
Only explicit confirmation sends the idle-only switch request. Cancelling leaves the host's project unchanged and
VS Code disconnected. Active or paused tasks prevent the switch; the panel reports the reason and blocks old-project requests.
The host process remains owned by Tauri/Web. Standalone Runtime owners without this control channel cannot follow folders.

The explicit attach binding follows the originating native VS Code window through Open Folder and Reload Window,
using its window callback route and parent-process identity. New windows remain unbound. Hosts that cannot supply
that identity require another explicit attach after an Extension Host restart. **Disconnect** clears the binding;
extension deactivation only detaches its bridge. When the host closes, restarts or switches projects, the extension
invalidates the binding and displays a warning. It never changes VS Code's folder or switches the host back automatically.
**Restart / Reconnect Runtime** retries the saved connection and asks again if a project switch is needed.
Background activation restores an explicitly selected binding only; it does not start a JVM in an unused window.
Connection failures share one replaceable notice in the panel and status bar. Retrying clears the old message,
and a successful connection clears the failure state, including while history and models are still loading.

Managed mode starts the JVM on a free loopback port. Existing mode verifies the owner lease, project path, scope,
instance and boot-token handshake, then registers an expiring editor bridge for this client. The bridge and its
private toolset do not alter persistent MCP configuration. Client identity keeps another client's active Runs and
approvals read-only, including when both UIs share the same Runtime.
The Web/Tauri host can explicitly end an attached VS Code task after confirmation, including when the editor
has exited. This uses the existing cancel operation; resuming, steering and approving remain with the original client.

Public history, attachments and tool details stay at `<workspace>/.harnex/data` (or its registered override).
Managed VS Code keeps execution state under `runtime-stores/vscode-<storage-id>`; Tauri uses `runtime-stores/tauri`.
Each execution directory retains an exclusive lock. Short cross-process transactions protect the shared history,
and an OS lock reserves each conversation until its active or paused Run is finished/cancelled. The two clients
can run different conversations concurrently and read each other's history; neither controls the other's live Run.
Shared user configuration stays under `~/.harnex`;
managed token/owner/log/manifest files stay under the window's
`~/.harnex/runtime-domains/vscode-*/` directory. New integrated terminals receive only the
`HARNEX_DOMAIN_MANIFEST` path for the current connection and, in managed mode, the companion native CLI directory on PATH. A trusted Client validates that manifest and reuses the Runtime;
it does not start a JVM or acquire the workspace data lock.

## History and tool details

If the left Activity Bar icon is missing, run **Harnex: Restore Sidebar Icon** from the Command Palette.
VS Code remembers moved views and containers across reinstalls. This command resets only the Harnex chat view
and its container to the primary sidebar; ordinary **Open Chat** preserves the chosen layout.

Use the clock button in the chat header or **Harnex: Show Conversation History** to browse the current project's
conversations. The picker supports title/preview filtering and loading more sessions. Open a conversation to
continue it, and use **加载更早消息** for earlier messages. A conversation occupied by the other client remains
readable and shows an explicit ownership notice; finish its task there before continuing here.
History comes from archived conversation messages. A missing or expired Run presentation (404/410) does not
prevent opening or restoring the conversation; available task details are added when retained by the Runtime.

Tool rows show arguments while the tool runs. Expanding a row fetches the full detail for that specific Run and
sequence, independently of the final assistant answer. Results update when the tool completes; replay preserves
expanded rows and the transcript scroll position.

On macOS, Provider references created by the Harnex.app Keychain flow are intentionally
desktop-Runtime-only. A VS Code-owned Runtime detects an unresolved reference before spawn
and reports an actionable error instead of running with an empty API key. Provide explicit
credentials for a VS Code-owned Runtime, or connect VS Code to the existing Harnex.app project Runtime.

## Layout

```
src/runtime/RuntimeManager.ts          Managed lifecycle or existing Runtime connection
src/runtime/attachedRuntime.ts         Shared trusted-manifest discovery and validation
src/client/HarnexClient.ts             REST + boot-token session (Bearer, host-only)
src/chat/ChatController.ts             host-owned chat state: run lifecycle + SSE pump + replay
src/webview/main.ts → media/chat.js    framework-free renderer (VS Code theme variables)
src/ui/ChatViewProvider.ts             sidebar webview container (CSP, nonce)
src/interactions/InteractionRouter.ts  approval events → native UX routing
src/interactions/DiffReview.ts         "审批即 diff": approval rendered as vscode.diff
src/commands.ts                        palette / context-menu commands
```
