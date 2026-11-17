# Third-party components / 第三方组件

Harnex's own code is covered by the root [Apache-2.0 license](../../LICENSE). Bundled dependencies retain their own licenses. This index identifies where to find their versions, notices and upstream source; it does not replace those license texts or relicense the complete installer under Apache-2.0.

| Component | Version/source inventory | Notices distributed with the component |
| --- | --- | --- |
| Java Runtime | The bundle's `jre/release`; the JDK distribution used by the native build | `jre/legal/<module>/LICENSE`, `COPYRIGHT` and other legal files |
| ripgrep, including SSH helper copies | [remote tools inventory](../../bin/assets/remote-tools.json), native build tool version | [ripgrep-MIT.txt](ripgrep-MIT.txt), copied to `bin/ripgrep-LICENSE.txt`; SSH copies have `remote-tools/<target>/LICENSE.txt` |
| Java libraries | [backend dependencies](../../backend/pom.xml); the exact JARs in `harnex.jar!/BOOT-INF/lib/` | Library `META-INF/LICENSE*`, `NOTICE*` or equivalent files remain inside the nested JARs where provided |
| Web UI and VS Code libraries | [frontend lockfile](../../frontend/package-lock.json) and [extension lockfile](../../extension/package-lock.json) | Upstream package LICENSE/NOTICE files and retained bundle license banners; preserve required attribution when rebuilding or repackaging |
| Native CLI, host and desktop dependencies | [CLI lockfile](../../src-cli/Cargo.lock), [desktop lockfile](../../src-tauri/Cargo.lock), [host manifest](../../host-runtime/Cargo.toml) | Each dependency's published source and license files; review the selected versions and target-specific components when distributing a new build |

## v1.0.0 Windows Java distribution

The existing Windows v1.0.0 Runtime contains Java **17.0.9** with the **Oracle No-Fee Terms and Conditions (NFTC)** text in `jre/legal/java.base/LICENSE`. It must not be described as an Apache-2.0 or Eclipse Temurin binary. Oracle's [licensing FAQ](https://www.oracle.com/java/technologies/javase/jdk-faqs.html) addresses jlink runtime images and no-fee redistribution; the license included with that exact binary controls. Check its redistribution conditions before charging for a product bundled with that runtime. The included license also identifies Oracle's [open-source download site](https://oss.oracle.com/sources/) for separately licensed components.

The native GitHub Actions workflow selects Eclipse Temurin 17 for subsequent builds. Temurin uses GPLv2 with the Classpath Exception; see the [Adoptium FAQ](https://adoptium.net/docs/faq/) and the [Temurin 17 release/source archive](https://github.com/adoptium/temurin17-binaries/releases). Keep the source/build identity and legal directory from the actual distribution; replacing a JRE changes the Runtime hashes and requires a fresh verified package.

## 再分发说明

Harnex 自有代码采用 Apache-2.0，第三方依赖继续使用各自许可证。上表列出了版本来源和随包声明的位置；不能把整套安装包的全部组件都标为 Apache-2.0。

现有 Windows v1.0.0 内置 Java 17.0.9，其随包许可证是 Oracle NFTC。免费使用和再分发应遵循这个具体版本的条款；准备收费分发含该 Runtime 的产品前，应重新核对相关限制。后续原生 CI 使用 Temurin 17，并保留其实际版本的许可证与来源信息。不要直接替换现有包内的 JRE：这会改变完整性哈希，应重新构建、验证并发布新的制品。

新版本打包时应保留 JRE 的完整 `legal/`、ripgrep 声明，以及依赖要求的版权、NOTICE 和许可文本。Java 库的嵌入声明、JS/CSS 的第三方归属和 Rust 依赖许可证需要按实际构建版本复核；本页是组件索引，不是所有依赖条款均已审计通过的声明。
