#!/usr/bin/env bash
# =============================================================================
# MySQL 清除脚本: 删除演练数据库 troubleboot_lab
#
# 用途: 完全重置演练环境(下次启动应用会自动重建库+重灌100万行),
#       或把本机恢复到演练前状态。
# 安全设计: 交互确认 + 只删 troubleboot_lab 一个库, 绝不碰其他数据库。
# =============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootroot}"

echo "目标: 删除 MySQL ${MYSQL_HOST}:${MYSQL_PORT} 上的数据库 [troubleboot_lab]"
echo "将使用的账号: ${MYSQL_USER}"
read -r -p "确认删除? 该操作不可恢复 (y/N): " confirm
if [[ ! "$confirm" =~ ^[yY]$ ]]; then
  echo "已取消, 未做任何修改"; exit 0
fi

mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" <<'SQL'
DROP DATABASE IF EXISTS troubleboot_lab;
SHOW DATABASES;
SQL

echo "==> troubleboot_lab 已删除。下次 ./scripts/start.sh 启动时会自动重建并重灌数据。"
