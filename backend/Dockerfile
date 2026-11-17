FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B -DskipTests dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# Copy the official glibc Node distribution for the target architecture, including npm/npx.
# Keep the LTS major explicit rather than following a moving `lts` alias.
FROM node:24-bookworm-slim AS node

FROM eclipse-temurin:17-jre-noble
WORKDIR /app
# Ubuntu 24.04 provides Python 3.12. Project packages belong in /workspace virtualenvs;
# leave the distribution's externally-managed Python protection intact.
# wget is also used by the Compose healthcheck; libstdc++/libatomic support the Node binary.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bash git ripgrep curl wget jq zip unzip ca-certificates \
        python3 python3-pip python3-venv python-is-python3 \
        libstdc++6 libatomic1 \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version && npx --version \
    && python --version && python -m pip --version
RUN mkdir -p /workspace
COPY --from=build /app/target/harnex-1.0.0-SNAPSHOT.jar app.jar
COPY bin ./bin
EXPOSE 8088
ENV HARNEX_SUPERVISOR_WORKSPACE="/workspace" \
    HARNEX_SUPERVISOR_HOST="0.0.0.0" \
    HARNEX_SUPERVISOR_PORT="8088" \
    HARNEX_USER_DATA_DIR="/root/.harnex" \
    HARNEX_JAR="/app/app.jar" \
    HARNEX_SUPERVISOR_JAVA_XMS="64m" \
    HARNEX_SUPERVISOR_JAVA_XMX="512m"
VOLUME ["/workspace","/root/.harnex"]
ENTRYPOINT ["node","/app/bin/harnex.mjs","supervisor"]
