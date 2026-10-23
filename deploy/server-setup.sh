#!/usr/bin/env bash
# 服务器一次性初始化：swap、Docker 加速、必要工具、内核参数。
# 用法（root）：bash deploy/server-setup.sh
#
# 只做“让构建跑得动、跑得稳”这一件事，不改应用配置、不碰腾讯云控制台防火墙。
set -euo pipefail

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 运行：sudo bash $0"

OS_ID=$(. /etc/os-release && echo "${ID:-unknown}")
log "系统：$(. /etc/os-release && echo "$PRETTY_NAME")"

# ---------- 1. 基础工具 ----------
if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl git openssl tar gzip >/dev/null
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y -q ca-certificates curl git openssl tar gzip >/dev/null
else
  warn "未识别的包管理器，跳过工具安装"
fi

# ---------- 2. swap（2C4G 构建 Next.js 时很关键） ----------
MEM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
if [ "$MEM_MB" -lt 8192 ] && ! swapon --show | grep -q .; then
  log "内存 ${MEM_MB}MB，创建 4GB swap"
  if [ ! -f /swapfile ]; then
    fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
else
  log "跳过 swap：内存 ${MEM_MB}MB，或已存在 swap"
fi

if [ ! -f /etc/sysctl.d/99-agents-anywhere.conf ]; then
  cat >/etc/sysctl.d/99-agents-anywhere.conf <<'EOF'
vm.swappiness = 10
vm.overcommit_memory = 1
fs.file-max = 1048576
net.core.somaxconn = 1024
EOF
  sysctl --system >/dev/null 2>&1 || true
fi

# ---------- 3. Docker ----------
if ! command -v docker >/dev/null 2>&1; then
  warn "未检测到 Docker，尝试安装"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get install -y -qq docker.io docker-compose-v2 >/dev/null
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y -q docker docker-compose-plugin >/dev/null
  fi
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker info >/dev/null 2>&1 || die "Docker 守护进程没起来，先执行：systemctl status docker"

if ! docker compose version >/dev/null 2>&1; then
  warn "缺少 docker compose 插件，尝试安装"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get install -y -qq docker-compose-v2 >/dev/null || true
  fi
  docker compose version >/dev/null 2>&1 || die "docker compose 仍不可用，请先在控制台重装带 Compose 的 Docker 镜像"
fi
log "Docker $(docker --version | awk '{print $3}' | tr -d ,) / $(docker compose version --short 2>/dev/null || echo compose-ok)"

# ---------- 4. Docker Hub 镜像加速 ----------
# 腾讯云内网镜像源只在腾讯云机器上可用，先探测再写入，避免写坏 daemon.json。
MIRROR="https://mirror.ccs.tencentyun.com"
if curl -fsS --max-time 6 -o /dev/null "$MIRROR/v2/" 2>/dev/null \
  || curl -fsS --max-time 6 -o /dev/null "$MIRROR/v2/library/redis/tags/list" 2>/dev/null; then
  log "写入 Docker 镜像加速：$MIRROR"
  python3 - "$MIRROR" <<'PY'
import json, os, sys
path = "/etc/docker/daemon.json"
mirror = sys.argv[1]
data = {}
if os.path.exists(path):
    try:
        with open(path) as fh:
            data = json.load(fh) or {}
    except Exception:
        data = {}
mirrors = data.get("registry-mirrors") or []
if mirror not in mirrors:
    mirrors.insert(0, mirror)
data["registry-mirrors"] = mirrors
os.makedirs("/etc/docker", exist_ok=True)
with open(path, "w") as fh:
    json.dump(data, fh, indent=2, ensure_ascii=False)
PY
  systemctl restart docker
  sleep 2
  docker info >/dev/null 2>&1 || die "改完 daemon.json 后 Docker 起不来，检查 /etc/docker/daemon.json"
else
  warn "腾讯云镜像源不可达（境外地域常见），跳过；若拉取镜像慢，可自行配 registry-mirrors"
fi

# ---------- 5. 预拉基础镜像（可选，失败不影响） ----------
for img in node:22-bookworm-slim python:3.12-slim postgres:17-alpine redis:8-alpine alpine:3; do
  docker pull -q "$img" >/dev/null 2>&1 && log "已拉取 $img" || warn "拉取失败：$img（稍后构建时会再试）"
done

log "服务器初始化完成。下一步：bash deploy/install.sh"
warn "别忘了在【腾讯云控制台 → 轻量应用服务器 → 防火墙】放行 TCP 18080（或你自定义的端口）。"
