# Harnex Runtime

Platform-specific, UI-free Runtime companion for `harnex.harnex-vscode`.

It contains only the immutable, checksummed `runtime/` bundle (jlink JRE, `harnex.jar`
and the target-native thin CLI launcher and ripgrep).
It does not start a JVM, create a Runtime Domain, choose a workspace or store runtime state.
The owning Harnex extension host performs those lifecycle operations.

Build and stage a bundle before packaging:

Install a self-contained ripgrep binary on the build host first (official release binary,
or `cargo install ripgrep --version 15.1.0 --locked --no-default-features`). Avoid binaries
linked to build-host libraries such as Homebrew's or a Linux distribution's PCRE2. The builder copies `rg` from PATH into `bin/`;
use `--rg-bin /path/to/rg` (or `HARNEX_RG_PATH`) for an explicit executable. Cross-target
builds require a target-platform executable. The bundle includes its license and hashes,
and macOS signs it before hashing alongside the other native code.

```bash
npm run build:runtime:companion
npx @vscode/vsce package --target darwin-arm64  # Apple Silicon
npx @vscode/vsce package --target darwin-x64    # Intel
# Windows uses --target win32-x64
```

Darwin companion code is signed before `runtime-bundle.json` hashes are generated. Release
artifacts are produced on native ARM/Intel Mac runners; cross-architecture jlink images are
not guessed or assembled on another host.
