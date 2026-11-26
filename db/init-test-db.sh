#!/usr/bin/env bash
# =============================================================
# 初始化后端测试库 payroll_test（本地 3307 容器）
#
# 只重建 payroll_test，绝不碰开发库 payroll。
# 测试基类 AbstractDbTest 每次运行前会 TRUNCATE 所有业务表并重建基线数据，
# 所以这里只要保证「库存在 + 表结构正确 + 应用账号有权限」即可。
#
#   ./db/init-test-db.sh
# =============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "缺少 .env（参考 .env.example 创建）" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

CONTAINER="${PAYROLL_MYSQL_CONTAINER:-payroll-mysql}"
DB=payroll_test

echo "==> 重建 ${DB}（仅测试库）"
docker exec "$CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "DROP DATABASE IF EXISTS ${DB}; CREATE DATABASE ${DB} DEFAULT CHARSET utf8mb4;"

echo "==> 导入表结构与初始化科目"
docker exec -i "$CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "${DB}" \
  < db/schema-mysql.sql

echo "==> 授权应用账号"
docker exec "$CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "GRANT ALL PRIVILEGES ON ${DB}.* TO '${MYSQL_USER}'@'%'; FLUSH PRIVILEGES;"

echo "==> 完成：${DB} 已就绪，可运行 mvn -B test"
