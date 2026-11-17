# Docker 离线交付与升级

这里部署的是 Docker 后端和 WebUI。桌面、VSIX、原生 CLI/TUI 的离线包由 `scripts/build/offline-kit.mjs` 构建。

普通开源用户直接从源码执行 `docker compose up -d --build`，无需 `.env` 或预构建的 Harnex 镜像，启动后在 Web 设置中配置模型。默认工作区与可选宿主机挂载见[部署方案](../../docs/04_部署方案.md#6-docker-compose-部署)。本文保留给需要自行制作镜像离线包或使用私有镜像仓库的部署方。

## 构建容器离线包

构建机需要 Bash、Docker 和 tar；目标镜像必须已发布到构建机可访问的仓库。示例在仓库根目录执行：

```bash
bash scripts/build/build-docker-offline.sh 1.0.0 \
  registry.example/team/backend:1.0.0 registry.example/team/frontend:1.0.0 linux/amd64
```

第四个参数支持 `linux/amd64`（默认）和 `linux/arm64`；第五个参数可指定输出目录。
默认输出：`dist/docker-offline/1.0.0-linux-amd64/harnex-1.0.0-offline-linux-amd64.tar.gz` 及其 `.sha256`。
镜像拉取后验证实际平台，按不可变 image ID 导出；归档中的 `images.env` 保存确切 ID 和平台。
包中仅含镜像、配置样例、安装/升级脚本和镜像版 Compose，不含源码、用户 `.env` 或本地构建 override。

仓库根目录的 `docker-compose.yml` 是镜像版定义；`docker-compose.override.yml` 添加源码构建配置。
日常 `docker compose up --build` 会加载标准 override；显式使用 `-f` 构建时需要同时指定两个文件。

后端默认挂载两个命名卷：`HARNEX-workspace:/workspace` 保存项目文件、`.harnex/data` 和 `.harnex/skills`，
`HARNEX-user:/root/.harnex` 保存共享配置、用户 Skills 和 `personal/` 下的个人会话。
Web 的会话与固定项目 Agent 按需启动；健康检查验证管理进程，未打开的 Runtime 不影响容器健康状态。
Runtime 日志默认写入各自数据目录的 `diagnostics/logs`。此布局面向全新部署，不迁移旧版独立 data/skills 卷。

## 安装与离线升级

目标机需要 Bash、支持 Linux 容器的 Docker，以及 Docker Compose 2.24 以上版本。
先验证外层归档的校验和，再解压。离线安装脚本要求 `.env` 存在，复制 `.env.example` 即可；模型可在服务启动后通过 Web 设置。随后执行：

```bash
bash install.sh
```

升级时解压新包，把现有部署的 `.env` 复制到新包目录，再运行 `bash upgrade.sh`。
也可执行 `bash upgrade.sh /path/to/deployment`：镜像来自脚本所在的新包，Compose 与 `.env` 来自显式部署目录。
脚本不会修改 `.env`。已有容器的 Compose project 标签会被复用，因此版本目录变化不会新建另一组数据卷。
首次部署默认项目名为 `harnex`；可通过环境变量 `COMPOSE_PROJECT_NAME` 明确指定。
若现有容器与该项目名冲突，脚本会拒绝接管。

离线包默认使用本地归档，即使环境中残留 `IMAGE_TAG` 等 CI 变量，也不会自动联网。
镜像加载后创建按内容 ID 命名的本地引用，再以 `--no-build --pull never` 启动。
健康检查使用 [Compose 的 `--wait` 和 `--wait-timeout`](https://docs.docker.com/reference/cli/docker/compose/up/)，
随后精确校验两个容器的镜像、项目、运行状态及 `healthy` 状态；`unhealthy`、缺少健康检查和超时均返回失败。
`HARNEX_DEPLOY_TIMEOUT` 可设置等待秒数，默认为 120。

## 仓库升级与回滚

仓库升级使用 `bash upgrade.sh --registry [deployment-directory]`，并设置
`HARBOR_REGISTRY`、`HARBOR_PROJECT`、`IMAGE_TAG`；认证可使用已有 Docker 登录状态，或成对提供
`HARBOR_USER`、`HARBOR_PWD`。目标镜像会按 Docker server 的实际平台拉取。
没有离线清单的部署目录也兼容这些环境变量驱动的仓库模式。

自动化部署可使用 `--registry-stdin`：以上五个字段按顺序以 NUL 分隔，经 SSH stdin 传输；凭据不会嵌入远端命令。
SSH 使用严格主机身份校验，需预先配置经过核验的 `known_hosts`。

升级前读取两个实际容器的 image ID，并创建不可变本地引用保留旧镜像。加载、拉取失败不会切换服务。
激活失败时恢复两份旧镜像，并再次验证健康状态；回滚成功仍返回非零，回滚失败会明确报错。
首次部署没有上一版可回滚；首次部署不完整时，仅允许重试相同镜像，不会覆盖身份不明的容器。
成功记录写入 `.harnex-current-images.env`，升级前记录写入 `.harnex-previous-images.env`；它们不包含凭据。
不会执行全局镜像清理，也不会删除数据卷。

回滚范围是容器镜像，不能撤销业务数据、数据库迁移或用户配置变更。跨不兼容版本升级前应备份数据与配置。
同一 OS 用户的部署通过 `~/.harnex/docker-deploy.lock` 串行化，可用 `HARNEX_DEPLOY_LOCK_DIR` 指定共享的绝对路径。
异常强制终止可能留下锁；确认没有部署进程后，用 `rmdir` 清理该空锁目录再重试。

## 验证

部署后检查服务健康状态，并实际验证工作区、用户配置、历史与 Skills 在重建后保留。独立故障注入、Docker 和 SSH 自动验收脚本保留在内部仓。

普通用户部署只需 Git、Docker 和 Compose；Maven/JDK、前端构建工具以及 Agent 的 Python/Node 环境均由 Dockerfile 提供。
