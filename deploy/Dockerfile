# 自托管构建用的 Server 镜像。
# 与仓库里的 docker/Dockerfile 保持一致，只多出两处网络相关的默认值：
#   - COREPACK_NPM_REGISTRY：corepack 下载 yarn 4.x 时走 npm 镜像（大陆服务器直连 npmjs 往往很慢）
#   - COREPACK_ENABLE_DOWNLOAD_PROMPT：非交互构建时不弹确认
# 上游 docker/Dockerfile 有变化时，同步更新这里。

FROM node:22-bookworm-slim AS web-static

ARG YARN_REGISTRY=

ENV COREPACK_NPM_REGISTRY=${YARN_REGISTRY:-https://registry.npmmirror.com} \
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    NEXT_OUTPUT=export \
    AGENTS_ANYWHERE_API=

WORKDIR /app/web-next

COPY web-next/package.json web-next/.yarnrc.yml ./
RUN corepack enable \
    && if [ -n "$YARN_REGISTRY" ]; then yarn config set npmRegistryServer "$YARN_REGISTRY"; fi \
    && YARN_ENABLE_IMMUTABLE_INSTALLS=false yarn install

COPY web-next ./
RUN yarn build


FROM python:3.12-slim AS server

ARG APT_MIRROR=
ARG PIP_INDEX_URL=

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PYTHONPATH=/app/server \
    AGENT_SERVER_DB_BACKEND=postgres \
    AGENT_SERVER_HOST=0.0.0.0 \
    AGENT_SERVER_STATIC_DIR=/app/web-static

WORKDIR /app

RUN if [ -n "$APT_MIRROR" ]; then \
        sed -i "s|http://deb.debian.org/debian|$APT_MIRROR|g; s|http://deb.debian.org/debian-security|$APT_MIRROR-security|g" /etc/apt/sources.list.d/debian.sources; \
    fi \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tini git \
    && rm -rf /var/lib/apt/lists/* \
    && if [ -n "$PIP_INDEX_URL" ]; then pip config set global.index-url "$PIP_INDEX_URL"; fi \
    && pip install --no-cache-dir uv

COPY server/pyproject.toml ./server/
# 国内网络下 uv 去 GitHub 拉 git 依赖经常被 TLS 中断。构建上下文里带了 vendor 副本时，
# 把 httpx-s3-client 的 git 源改成本地路径，整个构建不再访问 GitHub；没有 vendor 时保持上游配置。
COPY server/vendor ./server/vendor
RUN if [ -f server/vendor/httpx-s3-client/pyproject.toml ]; then \
        sed -i 's|httpx-s3-client = { git = "https://github.com/t4wefan/httpx-s3-client.git" }|httpx-s3-client = { path = "vendor/httpx-s3-client" }|' server/pyproject.toml \
        && grep -n "httpx-s3-client" server/pyproject.toml; \
    fi

RUN if [ -n "$PIP_INDEX_URL" ]; then export UV_INDEX_URL="$PIP_INDEX_URL"; fi \
    && cd server && uv sync --no-dev --no-install-project

COPY server ./server
COPY --from=web-static /app/web-next/out ./web-static

RUN mkdir -p /data
VOLUME ["/data"]

EXPOSE 8000
WORKDIR /app/server
ENTRYPOINT ["tini", "--"]
CMD ["uv", "run", "--no-sync", "python", "-m", "agent_server.main"]
