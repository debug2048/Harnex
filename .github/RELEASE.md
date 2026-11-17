# Native release builds

Run **Build native release assets** in GitHub Actions with an existing version tag.
The workflow builds the exact source commit referenced by that tag on native
Windows, macOS Apple Silicon / Intel, and Linux x64 / ARM64 runners.
Select `unix` to build only the four macOS/Linux targets when Windows assets have
already passed validation for the same source commit.

The workflow configuration is checked out separately from the tagged source.
This lets maintainers add or repair build automation without moving a published
tag or compiling unpublished product changes. Every platform manifest records
the tagged source commit, rather than the workflow commit.

Successful jobs retain installers, offline kits, dual VSIX packages and native
CLI packages as Actions artifacts for seven days. Linux x64 also includes the
shared npm package, backend JAR and Web UI archive. The public workflow verifies
Runtime bundle integrity on every platform and the extracted AppImage on Linux.
Standalone test suites, fixtures and acceptance runners stay in the internal
repository. Embedded tests in application source may remain. Build and checksum
success does not establish Runtime lifecycle, terminal, extension or manual
desktop acceptance; record those results separately.

Download the successful platform artifacts, combine them with any existing
verified assets from the same source commit, then run the all-platform verifier:

```bash
GITHUB_SHA="<tagged source commit>" node scripts/build/release-assets.mjs --verify dist/release-all
```

Upload the complete verified set and its generated `SHA256SUMS` to the matching
GitHub Release. The build workflow never changes repository visibility or
automatically publishes a partial set of assets.

To complete an existing Release, run **Complete multiplatform release** with
the same version tag and one or more comma-separated native build run IDs. Every
selected platform job and its required checks must have passed; a retry can
reuse successful platforms from an earlier run. It downloads the
existing assets and the missing platform artifacts on a GitHub runner, verifies
all five source manifests and checksums, then uploads only missing files. It
preserves existing program bytes, replaces the combined checksum list, and
updates the platform table and verification notes. Existing assets from another
source commit, failed build jobs, unexpected files, and immutable releases stop
the workflow before upload.

Linux AppImage packaging resolves Java libraries from the staged Runtime and
preserves manifest-covered Runtime files when linuxdeploy adjusts desktop ELF
paths. After packaging, the AppImage is extracted and its embedded Runtime
hashes are checked again. The guard regression tests remain internal.

macOS builds use ad-hoc signing. Apple Developer ID signing and notarization
require the separate signing credentials and acceptance process described in
the deployment guide. Windows signing and automatic updater publication are
also separate release steps.

These public release workflows and their helper scripts are maintained in the
internal source repository and included in every reviewed public export. The
export policy lists each public CI file explicitly and requires it to exist;
internal-only workflows and actions remain excluded. When adding or removing a
public CI file, update both the export allowlist and required-file list, then
run the public export tests before exporting the committed source.

## Preparing to open the repository

Keep the repository private until the maintainer decides to make it public. A
successful release build does not change visibility or enable a distribution
channel. Review the source export, reachable Git history, retained Actions logs
and screenshots for credentials or private material; retain the review evidence
outside the public source. Preserve third-party license notices and document the
actual JDK distribution used by each artifact (see [component notices](../docs/licenses/README.md)).

Before announcing a release, check the README download links, platform choices,
first-run model setup and the accuracy of the published acceptance results.
Keep unsigned/unnotarized status and pending manual tests visible. Only advertise
npm or Marketplace installation for versions actually available there.

The source export includes [contribution guidance](../CONTRIBUTING.md),
[security reporting instructions](../SECURITY.md) and both Issue forms. External
feedback currently uses Issues; PR creation is limited to collaborators.

After the maintainer changes the repository to public, enable **Settings →
Advanced Security → Private vulnerability reporting**. GitHub makes this
feature available for public repositories; adding SECURITY.md alone does not
enable the form. Verify the **Report a vulnerability** button and check downloads
without signing in. Keep the internal source repository private. See
[GitHub's instructions](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository).
