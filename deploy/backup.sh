#!/usr/bin/env bash
# 备份：PostgreSQL 逻辑备份 + 附件卷打包，保留最近 7 份。
# 用法（在 deploy 目录）：bash backup.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

[ -f .env ] || { echo "缺少 .env，先跑 install.sh" >&2; exit 1; }
mkdir -p backups
STAMP=$(date +%Y%m%d-%H%M%S)

echo "==> 导出数据库"
docker compose --env-file .env exec -T postgres-next \
  pg_dump -U agents_anywhere -d agents_anywhere --no-owner \
  | gzip >"backups/db-$STAMP.sql.gz"

VOLUME=$(docker volume ls --format '{{.Name}}' | grep -E 'agents-anywhere_agents-anywhere-files-next$' | head -n1 || true)
if [ -n "$VOLUME" ]; then
  echo "==> 打包附件卷 $VOLUME"
  docker run --rm \
    -v "$VOLUME:/data:ro" \
    -v "$PWD/backups:/backup" \
    alpine tar czf "/backup/files-$STAMP.tar.gz" -C /data . >/dev/null
else
  echo "[!] 没找到附件卷，跳过（可能还没有上传过文件）"
fi

ls -1t backups/*.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
echo "==> 完成：backups/db-$STAMP.sql.gz"
ls -lh backups | tail -n 5
