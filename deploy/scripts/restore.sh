#!/usr/bin/env bash
# 从备份恢复数据库。用法（备份文件属 root、权限 600，需 sudo）：
#   sudo ./scripts/restore.sh /var/backups/swufe-homework/homework-YYYYMMDD-HHMM.dump
#
# 流程：先恢复到临时库 <DB>_restore —— 失败则删掉临时库，原库和 server 都不受影响；
# 成功后才停 server，把原库改名为 <DB>_old_<时间> 留作回滚，临时库改名为 <DB>，再启动 server。
# 数据库名和用户取 postgres 容器自己的环境变量，不 source .env。
# shellcheck disable=SC2016  # $POSTGRES_*、$NEW、$OLD 在容器内展开
set -euo pipefail
FILE="${1:?用法: $0 <备份文件.dump>}"
[[ -r "$FILE" ]] || { echo "读不到 $FILE（备份文件权限 600 root，请用 sudo）" >&2; exit 1; }
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR"
TS="$(TZ=Asia/Shanghai date +%Y%m%d%H%M)"

psql_c() { # 在 postgres 库上执行一条 SQL；$NEW/$OLD/$POSTGRES_* 在容器内展开
  docker compose exec -T -e TS="$TS" postgres sh -c \
    'NEW="${POSTGRES_DB}_restore"; OLD="${POSTGRES_DB}_old_$TS"; psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -c "'"$1"'"'
}

read -r -p "将用 $FILE 覆盖当前数据库（原库会改名保留），输入 yes 继续：" ok
[[ "$ok" == "yes" ]] || exit 1

echo "1/3 恢复到临时库…"
psql_c 'DROP DATABASE IF EXISTS \"$NEW\" WITH (FORCE);'
psql_c 'CREATE DATABASE \"$NEW\" OWNER \"$POSTGRES_USER\";'
if ! docker compose exec -T postgres sh -c \
     'pg_restore -U "$POSTGRES_USER" -d "${POSTGRES_DB}_restore" --no-owner --exit-on-error' < "$FILE"; then
  psql_c 'DROP DATABASE IF EXISTS \"$NEW\" WITH (FORCE);' || true
  echo "恢复失败：临时库已删除，原数据库和 server 未改动。" >&2
  exit 1
fi

echo "2/3 切换数据库（短暂停 server）…"
docker compose stop server
psql_c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\$POSTGRES_DB' AND pid <> pg_backend_pid();" > /dev/null
if ! { psql_c 'ALTER DATABASE \"$POSTGRES_DB\" RENAME TO \"$OLD\";' \
       && psql_c 'ALTER DATABASE \"$NEW\" RENAME TO \"$POSTGRES_DB\";'; }; then
  echo "切换失败，请按 deploy/README.md「恢复失败怎么办」检查 \\l 后手动改名，再 docker compose start server" >&2
  exit 1
fi

echo "3/3 启动 server…"
docker compose start server
echo "恢复完成。原库保留为 <库名>_old_$TS，确认无误后可删除："
echo "  docker compose exec postgres sh -c 'dropdb -U \"\$POSTGRES_USER\" \"\${POSTGRES_DB}_old_$TS\"'"
