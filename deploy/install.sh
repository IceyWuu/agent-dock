#!/usr/bin/env bash
# 构建并启动 Agents Anywhere 自托管服务。
# 用法（root，在 deploy 目录）：bash install.sh [--port 18080] [--ip 1.2.3.4]
#
# 首次运行会：
#   1. 生成 deploy/.env（数据库密码、签名密钥、访问地址）
#   2. 构建 server 镜像（首次约 15-30 分钟，取决于网络）
#   3. 起 PostgreSQL / Redis / 迁移 / Server
#   4. 等健康检查通过，打印首次创建管理员用的 setup token
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

PORT="${AA_PORT:-}"
IP="${AA_PUBLIC_IP:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port 需要一个值}"; shift 2 ;;
    --ip)   IP="${2:?--ip 需要一个值}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "没装 Docker，先跑：bash server-setup.sh"
docker info >/dev/null 2>&1 || die "Docker 没在运行：systemctl start docker"
docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件，先跑：bash server-setup.sh"
command -v openssl >/dev/null 2>&1 || die "缺少 openssl"

if [ ! -f .env ]; then
  [ -n "$PORT" ] || PORT=18080
  if [ -z "$IP" ]; then
    IP=$(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null \
      || curl -fsS --max-time 6 https://ifconfig.me 2>/dev/null \
      || true)
  fi
  [ -n "$IP" ] || die "没探测到公网 IP，请显式指定：bash install.sh --ip 你的公网IP"

  log "生成 deploy/.env（端口 $PORT，访问地址 http://$IP:$PORT）"
  umask 077
  cat >.env <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
AGENT_SERVER_SECRET=$(openssl rand -hex 32)

AGENTS_ANYWHERE_WEB_PORT=$PORT
AGENT_SERVER_PUBLIC_ORIGIN=http://$IP:$PORT
AGENT_SERVER_CORS_ORIGINS=http://$IP:$PORT,http://127.0.0.1:$PORT,http://localhost:$PORT
AGENT_SERVER_SETUP_TOKEN_TTL=3600

APT_MIRROR=https://mirrors.ustc.edu.cn/debian
PIP_INDEX_URL=https://mirrors.ustc.edu.cn/pypi/simple
YARN_REGISTRY=https://registry.npmmirror.com

REDIS_MAXMEMORY=256mb
EOF
  chmod 600 .env
else
  log "已存在 deploy/.env，沿用现有配置"
fi

# 从 .env 读回端口，后续健康检查用
PORT=$(sed -n 's/^AGENTS_ANYWHERE_WEB_PORT=//p' .env | tail -n1)
PORT=${PORT:-18080}
PUBLIC_ORIGIN=$(sed -n 's/^AGENT_SERVER_PUBLIC_ORIGIN=//p' .env | tail -n1)

log "源码版本：$(git -C .. describe --tags --always 2>/dev/null || echo unknown)"

log "校验 compose 配置"
docker compose --env-file .env config -q

log "开始构建镜像（首次较慢，可另开窗口 tail -f 观察）"
if ! docker compose --env-file .env build; then
  warn "构建失败。常见原因：内存不足（OOM / exit 137）或镜像源不通。"
  warn "检查：free -h；docker compose --env-file .env build --progress=plain"
  exit 1
fi

log "启动容器"
docker compose --env-file .env up -d

log "等待服务就绪（最多 3 分钟）"
READY=0
for _ in $(seq 1 60); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/v2/health/ready" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 3
done

if [ "$READY" -ne 1 ]; then
  warn "健康检查没有通过，最近日志："
  docker compose --env-file .env logs --tail=60 server-next migrate-next || true
  exit 1
fi
log "服务已就绪：$PUBLIC_ORIGIN"

NEEDS_BOOTSTRAP=$(curl -fsS "http://127.0.0.1:$PORT/api/v2/auth/config" 2>/dev/null \
  | grep -o '"needsBootstrap":[a-z]*' | cut -d: -f2 || true)

if [ "$NEEDS_BOOTSTRAP" = "true" ]; then
  # 打开页面会重新生成过期 token，先触发一次再读日志，拿到的才是有效的。
  curl -fsS "http://127.0.0.1:$PORT/api/v2/auth/config" >/dev/null 2>&1 || true
  TOKEN=$(docker compose --env-file .env logs server-next 2>&1 \
    | grep -oE 'setup-token: [A-Za-z0-9_-]+' | tail -n1 | awk '{print $2}' || true)
  cat <<EOF

────────────────────────────────────────────
 服务地址：$PUBLIC_ORIGIN
 首次初始化（有效期 1 小时）：
   setup token: ${TOKEN:-（没抓到，执行 docker compose logs server-next | grep setup-token）}
 打开上面的地址 → 粘贴 token → 创建管理员账号
────────────────────────────────────────────
 常用命令（在 $SCRIPT_DIR 下）：
   docker compose ps                 # 容器状态
   docker compose logs -f server-next  # 服务日志
   bash backup.sh                    # 备份数据库和附件
   bash upgrade.sh [v2.x.y]          # 备份并按 tag 重建
EOF
else
  cat <<EOF

────────────────────────────────────────────
 服务地址：$PUBLIC_ORIGIN
 该实例已完成初始化，直接登录即可。
────────────────────────────────────────────
EOF
fi
