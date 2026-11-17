# Installation and deployment

[Home](../../README.md) · [User guide](user-guide.md) · [详细中文部署指南](../04_部署方案.md)

Start with a Release installer or complete offline package. Docker is available for a source-based Web workbench. Native installers and Runtime Bundles include Java; npm uses tools installed on your machine and is an option only for versions published to that registry.

## First run

Download an installer or complete `_offline` package from [GitHub Releases](https://github.com/debug2048/Harnex/releases/latest), matching the platform table below. GitHub's generated **Source code** archives are for building from source. A native CLI npm archive contains only the CLI, not the complete Runtime.

Download `SHA256SUMS` alongside your selected artifact. Compare its entry with `Get-FileHash -Algorithm SHA256 .\filename` in Windows PowerShell, `sha256sum filename` on Linux, or `shasum -a 256 filename` on macOS. Use a whole-list check only if all listed files are present. Re-download a file whose checksum does not match.

- **Windows:** run the EXE installer; WebView2 is required. v1.0.0 is unsigned and may trigger publisher or SmartScreen prompts. Verify the source and hash before following OS prompts; managed devices may require administrator assistance.
- **macOS:** open the matching DMG, copy Harnex to Applications, and launch it. v1.0.0 uses ad-hoc signing without notarization. After verifying the source, follow the per-app guidance in System Settings → Privacy & Security if Gatekeeper blocks it; do not disable Gatekeeper globally.
- **Linux:** install the DEB/RPM with the distribution's package manager and dependencies. AppImage also requires compatible system libraries. See the platform boundaries below.
- **VS Code:** install the platform Runtime VSIX first, then the UI VSIX, and reload the window. Marketplace distribution is not required.

1. Start with a personal conversation. In **Settings → Models**, configure the protocol, provider URL, API key when required and exact model ID, then select the model. A model service and any API credits are supplied by the user.
2. Ask “Introduce yourself in one sentence.” For a failed request, check the endpoint, protocol, model ID, credentials and network first.
3. Open your own project and ask “Read this project and describe its structure without modifying files.” Confirm the workspace and execution environment before approving subsequent writes or commands.
4. Restart the application and confirm that history remains available. Back up the actual user/project data paths shown in diagnostics before upgrading.

The v1.0.0 release records Runtime lifecycle/data-retention checks, Unix PTY tests, extension contract tests and maintainer-confirmed Windows installation. macOS/Linux interactive desktop acceptance and VS Code Extension Host end-to-end acceptance remain pending; see the [release notes](https://github.com/debug2048/Harnex/releases/tag/v1.0.0). Report problems through the [Issue forms](https://github.com/debug2048/Harnex/issues/new/choose) with sanitized logs; use the [security policy](../../SECURITY.md) for vulnerabilities.

## Choose an installation

| Installation | Requirements on the user's machine | Best suited to |
| --- | --- | --- |
| Docker Web | Docker with Linux containers, Compose 2.24+, and a source checkout | A browser workbench on a local machine or server |
| Desktop installer | A supported OS and its desktop dependencies | A native application window |
| Portable Runtime Bundle | A supported OS; project-specific tools as needed | CLI/TUI and offline installation |
| npm | Node.js 18+, Java 17+ and ripgrep | Command-line installation using an existing toolchain |
| VS Code | VS Code 1.85+ and a platform Runtime companion or an existing project Runtime | Working inside the editor |
| Source | Node.js 20+, JDK 17+, Maven and ripgrep; Rust for native builds | Building or modifying Harnex |

Model requests require a provider and credentials. Configure them after startup in **Settings → Models**. A bundled JRE runs Harnex; it does not provide every compiler, package manager or external tool that your project may need.

## Platforms

Native build targets are:

| Target | Platform boundary |
| --- | --- |
| `win32-x64` | Windows 10 1903+ / Windows 11, x64 |
| `darwin-arm64` | macOS 13+, Apple Silicon |
| `darwin-x64` | macOS 13+, Intel |
| `linux-x64` | Linux x64 with glibc and WebKitGTK compatible with the Ubuntu 22.04 build baseline |
| `linux-arm64` | Linux ARM64 with the same library requirements |

Windows ARM64, 32-bit systems, Alpine/musl native packages and browser-hosted VS Code are outside the current distribution matrix. SSH Runtime servers support Linux x64/ARM64.

Check the release notes for available artifacts, verified platforms and signing status. The generic distribution workflow creates ad-hoc macOS builds; Developer ID signing and notarization use a separate release process. Do not assume every download is signed or notarized.

On Windows, avoid emoji and other supplementary-plane characters in the installation path because of the bundled Java 17 class-loading limitation. The distribution launcher supports Chinese and other basic multilingual-plane path characters.

## Docker

From a checkout of this repository:

```bash
docker compose up -d --build
```

Open **http://localhost:18081**. No `.env` file or model credentials are needed to start the interface. Configure a model in the workbench before submitting a request.

The first build downloads base images and dependencies. Maven, Java build tools and frontend build tools run inside the build containers.

### Workspace and persistence

The Agent works inside the backend container at `/workspace`. Docker manages two persistent volumes:

| Container path | Contents |
| --- | --- |
| `/workspace` | Project files, project history, assets and project skills |
| `/root/.harnex` | User configuration, personal conversations, user skills and authentication data |

Recreating containers preserves these volumes. `docker compose down` stops and removes containers; adding `-v` also removes the managed volumes and their data.

To use an existing host project, add its absolute path to `.env` without replacing other settings:

```dotenv
HARNEX_WORKSPACE_HOST=/srv/my-project
```

Then start with the workspace override:

```bash
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.workspace.yml up -d --build
```

The host directory must already exist. Commands still run inside the container, so its tools and permissions apply to the mounted project.

The backend image includes Java, Python, Node.js, Git, ripgrep, shell tools and OCR support. Install project Python packages in a virtual environment. Add a JDK, Maven, Go, Rust or browser dependencies to the runtime image when a project needs them.

### Access and ports

Default published ports bind to `127.0.0.1`. To use the workbench from another machine, an SSH tunnel can forward the frontend:

```bash
ssh -L 18081:127.0.0.1:18081 user@server
```

Then open `http://localhost:18081` on the client machine. For a shared HTTPS endpoint, configure an authenticated reverse proxy. See [.env.example](../../.env.example) for bind address and port settings.

### Update and troubleshoot

Stop active work and back up persistent data before upgrading. Check out the intended source version, then run:

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=200 backend frontend
```

Use the same override files used for the original deployment. Changes to environment variables or mounts require container recreation; `docker compose restart` does not apply them. Use `docker compose build --pull` when you also intend to refresh base images.

## Desktop and portable bundles

Choose an artifact matching the OS and CPU architecture. Desktop installers and portable Runtime Bundles contain a JRE, Harnex Runtime, native CLI and ripgrep.

An offline distribution contains:

```text
desktop/            Platform desktop installers, when included
vscode/             UI and platform Runtime VSIX packages
portable/runtime/   Runtime Bundle with native CLI/TUI
SHA256SUMS          File checksums
```

Launch the desktop application and select a workspace, or run `bin/harnex` (`bin/harnex.exe` on Windows) from the portable Runtime Bundle with an explicit project:

```bash
/path/to/portable/runtime/bin/harnex tui --workspace /path/to/project
```

Keep the Runtime Bundle's directory structure intact. Do not use the installation directory as the project workspace. Linux desktop packages still need system GUI libraries; use the appropriate package manager to install a DEB or RPM and its dependencies.

## npm

For a version available in the npm registry:

```bash
npm install -g harnex
harnex doctor
harnex
```

The npm launcher requires **Node.js 18+, Java 17+ and ripgrep**. `java` and `rg` must be available to the launcher; `HARNEX_RG_PATH` can specify ripgrep explicitly. The optional package for the current platform supplies the native CLI/TUI. An installation that omits optional dependencies may not provide the TUI.

You can also install a locally built npm archive with `npm install -g /path/to/harnex-<version>.tgz`. This does not bundle all of its optional dependencies or remove the Java/ripgrep requirements. Use the complete Runtime Bundle for an offline native installation.

Set `HARNEX_NO_SHORTCUT=1` before installation to skip desktop shortcut creation. `harnex shortcut` creates or repairs a shortcut; `harnex shortcut --uninstall` removes it.

For an update, install the intended npm version after stopping active work. `npm uninstall -g harnex` removes the package, while user and project data remain separate.

## VS Code

The extension has two parts:

| Package | Role |
| --- | --- |
| Harnex UI | Chat, editor context, diagnostics, diff review and Runtime lifecycle integration |
| Harnex Runtime companion | A verified Runtime Bundle for one platform |

For an offline install, install the platform Runtime companion and the UI VSIX using **Extensions: Install from VSIX**. The offline kit also provides `install-vscode.ps1` or `install-vscode.sh` to install both. Reload VS Code and open the Harnex sidebar.

The companion is optional when the extension can connect to an existing trusted Runtime for the same project. Without either a companion or an existing Runtime, the UI alone cannot execute tasks. An attached editor client does not take over the original host's process lifecycle.

VS Code provides project Agent tasks. Use the Web, desktop or standalone terminal client for personal conversations. Use the Marketplace listing only when that distribution has been published; installing VSIX files does not require a Marketplace listing.

## Source builds

Install Node.js 20+, JDK 17+, Maven and ripgrep. For native builds, install Rust 1.88+ and the operating system's native toolchain. Desktop packaging also needs `jlink` and the platform GUI build dependencies.

From the repository root:

```bash
npm --prefix frontend ci
npm run dev
```

The development workbench is at **http://localhost:18081**; the backend API is at `:8088`. Stop development processes with `npm run dev:stop`.

To use a separate configuration directory or frontend port:

```bash
npm run dev -- --config /path/to/config --frontend-port 19081
```

Source dependency verification uses npm 10.9.4. To install root development tools, run `npx --yes npm@10.9.4 ci --ignore-scripts --omit=optional`. Until the optional platform CLI packages are published, npm 12 may reject this lockfile during `npm ci`; local native builds compile those binaries from source.

Build the current platform's distribution:

```bash
npm run build:dist:check
npm run build:dist
```

Use `npm run build:dist -- --skip-desktop` for the Runtime, CLI/TUI and VSIX packages without a desktop installer. Output goes to `dist/offline/<version>-<platform>-<arch>/`. Build each native target on the corresponding platform; the command is not a cross-platform release service.

For a backend and Web/npm package without the native distribution, use `npm run build`. The build may download dependencies and does not publish to a registry by default.

## Data, backup and upgrades

| Data | Default location |
| --- | --- |
| User configuration, credentials and user skills | `~/.harnex/` |
| Personal history and assets | `~/.harnex/personal/` |
| Project history, assets and Runtime data | `<project>/.harnex/data/`, unless overridden |
| Project skills | `<project>/.harnex/skills/` |

Use Settings diagnostics to find actual directories. Project hosts may use separate execution subdirectories within a shared project data root. Back up the full root, including those subdirectories, rather than only one host's execution directory.

Stop all Runtime writers using the data before making a consistent backup. The Node/npm launcher provides:

```bash
harnex backup --workspace /path/to/project --out /path/to/backups
harnex backup --personal --out /path/to/backups
```

These commands archive the selected project or personal data; back up user configuration and skills separately. The native CLI does not implement the Node launcher's backup command. Native users can copy the corresponding directories after stopping writers.

`HARNEX_USER_DATA_DIR` selects a different user data root. It changes future reads and writes but does not migrate existing files automatically. Keep data directories separate from the project root, installation directory and user configuration root.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Model request fails | Provider URL, protocol profile, exact model ID, credentials and connectivity |
| npm TUI is unavailable | Matching optional CLI package, Java and ripgrep; use a complete Runtime Bundle if needed |
| VS Code cannot start a Runtime | Installed platform companion or a trusted existing Runtime for this project |
| A port is occupied | Stop the relevant instance or choose a different port; internal native Runtime ports are allocated automatically |
| A project tool is missing | Install it in the actual execution environment: host, container or SSH server |
| Data appears missing after an upgrade | Actual user/project data paths, mounted volumes and explicit path overrides |

Include the version, OS/architecture, client, reproduction steps and sanitized logs when reporting a problem. The [user guide](user-guide.md) explains task execution and permissions.
