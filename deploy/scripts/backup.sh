#!/usr/bin/env bash
# 每日数据库备份：pg_dump 自定义格式（已压缩），保留 7 天。上传目录不备份。
# crontab（服务器时区设为 Asia/Shanghai）：
#   30 3 * * * /opt/swufe-homework/deploy/scripts/backup.sh >> /var/log/swufe-backup.log 2>&1
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/swufe-homework}"
KEEP_DAYS="${KEEP_DAYS:-7}"
mkdir -p "$BACKUP_DIR"
umask 077

cd "$DEPLOY_DIR"
# shellcheck source=/dev/null
set -a; . ./.env; set +a
DB="${POSTGRES_DB:-homework}"; DBUSER="${POSTGRES_USER:-homework}"
TS="$(TZ=Asia/Shanghai date +%Y%m%d-%H%M)"
OUT="$BACKUP_DIR/homework-$TS.dump"

docker compose exec -T postgres pg_dump -U "$DBUSER" -d "$DB" -Fc -Z 6 > "$OUT.part"
# 校验能读出目录，再落盘
docker compose exec -T postgres pg_restore --list < "$OUT.part" > /dev/null
mv "$OUT.part" "$OUT"

find "$BACKUP_DIR" -name 'homework-*.dump' -mtime +$((KEEP_DAYS - 1)) -delete
find "$BACKUP_DIR" -name '*.part' -mtime +0 -delete
echo "$(date '+%F %T') ok $OUT $(du -h "$OUT" | cut -f1)"
