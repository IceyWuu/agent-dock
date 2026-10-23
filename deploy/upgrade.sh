#!/usr/bin/env bash
# 升级：备份 → 切版本 → 重建镜像 → 重启 → 健康检查。
# 用法（在 deploy 目录）：
#   bash upgrade.sh            # 已在 main 分支时 git pull；固定 tag 时保持现有代码
#   bash upgrade.sh v2.0.4     # 切到指定 tag（推荐：服务端与客户端版本要对齐）
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

REPO_DIR=$(cd .. && pwd)
PORT=$(sed -n 's/^AGENTS_ANYWHERE_WEB_PORT=//p' .env 2>/dev/null | tail -n1)
PORT=${PORT:-18080}
TARGET="${1:-}"

echo "==> 备份当前数据"
bash backup.sh

if [ -d "$REPO_DIR/.git" ]; then
  if [ -n "$TARGET" ]; then
    echo "==> 切换到 $TARGET"
    git -C "$REPO_DIR" fetch --depth 1 origin tag "$TARGET"
    git -C "$REPO_DIR" checkout -q "$TARGET"
  elif git -C "$REPO_DIR" symbolic-ref -q HEAD >/dev/null; then
    echo "==> git pull（当前分支）"
    git -C "$REPO_DIR" pull --ff-only
  else
    echo "[!] 当前固定在某个 tag，未指定目标版本，继续用现有代码重建"
  fi
else
  echo "[!] 不是 git 仓库，跳过拉取；请先把新代码覆盖上传到 $REPO_DIR"
fi

echo "==> 重建镜像"
docker compose --env-file .env build

echo "==> 重启服务（migrate 会自动跑数据库迁移）"
docker compose --env-file .env up -d

echo "==> 等待就绪"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/v2/health/ready" >/dev/null 2>&1; then
    echo "==> 升级完成，服务正常"
    exit 0
  fi
  sleep 3
done

echo "[x] 健康检查未通过，日志：" >&2
docker compose --env-file .env logs --tail=80 server-next migrate-next
exit 1
