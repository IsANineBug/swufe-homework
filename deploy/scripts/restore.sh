#!/usr/bin/env bash
# 从备份恢复数据库（会覆盖现有数据）。用法：./scripts/restore.sh /var/backups/swufe-homework/homework-YYYYMMDD-HHMM.dump
set -euo pipefail
FILE="${1:?用法: $0 <备份文件.dump>}"
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR"
set -a
# shellcheck source=/dev/null
. ./.env
set +a
DB="${POSTGRES_DB:-homework}"; DBUSER="${POSTGRES_USER:-homework}"

read -r -p "将用 $FILE 覆盖数据库 $DB，输入 yes 继续：" ok
[[ "$ok" == "yes" ]] || exit 1

docker compose stop server
docker compose exec -T postgres psql -U "$DBUSER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE);" -c "CREATE DATABASE \"$DB\" OWNER \"$DBUSER\";"
docker compose exec -T postgres pg_restore -U "$DBUSER" -d "$DB" --no-owner --exit-on-error < "$FILE"
docker compose start server
echo "恢复完成。"
