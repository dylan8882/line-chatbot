#!/usr/bin/env bash
# dev.sh — 本機開發環境一鍵啟動腳本
# 啟動順序：Docker (MySQL + Redis) → 後端 → 前端
# 結束：Ctrl+C 同時關閉前後端，Docker 服務繼續在背景跑

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# ── 顏色輸出 ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ── 讀取 .env ─────────────────────────────────────────────────────────────────
if [ ! -f "$ENV_FILE" ]; then
  log_error ".env 檔案不存在，請先複製 .env.example 並填寫設定"
  exit 1
fi

# 載入 .env（忽略註解與空行）
set -o allexport
# shellcheck disable=SC1090
source <(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$')
set +o allexport

# 本機開發時 DB_HOST / REDIS_HOST 固定為 localhost
export DB_HOST=localhost
export REDIS_HOST=localhost

# ── Cleanup：Ctrl+C 時關閉前後端 process ─────────────────────────────────────
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  echo ""
  log_info "收到中斷信號，正在關閉..."
  [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null && log_ok "前端已停止"
  [ -n "$BACKEND_PID" ]  && kill "$BACKEND_PID"  2>/dev/null && log_ok "後端已停止"
  log_warn "Docker 服務（MySQL / Redis）仍在背景運行"
  log_info "如需停止 Docker：docker compose stop mysql redis"
  exit 0
}
trap cleanup INT TERM

# ── Step 1：啟動 Docker MySQL + Redis ────────────────────────────────────────
log_info "Step 1/3  啟動 Docker MySQL + Redis..."
cd "$SCRIPT_DIR"
docker compose up -d mysql redis

# 等待 MySQL healthy
log_info "等待 MySQL 就緒..."
for i in $(seq 1 30); do
  if docker compose exec -T mysql mysqladmin ping -h localhost --silent 2>/dev/null; then
    log_ok "MySQL 已就緒"
    break
  fi
  if [ "$i" -eq 30 ]; then
    log_error "MySQL 等待逾時，請確認 Docker 狀態"
    exit 1
  fi
  sleep 2
done

# 等待 Redis healthy
log_info "等待 Redis 就緒..."
for i in $(seq 1 15); do
  if docker compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then
    log_ok "Redis 已就緒"
    break
  fi
  if [ "$i" -eq 15 ]; then
    log_error "Redis 等待逾時，請確認 Docker 狀態"
    exit 1
  fi
  sleep 1
done

# ── Step 2：啟動後端 ──────────────────────────────────────────────────────────
log_info "Step 2/3  啟動後端（Spring Boot）..."
cd "$SCRIPT_DIR/backend"
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=${DB_TYPE:-mysql}" \
  > "$SCRIPT_DIR/backend.log" 2>&1 &
BACKEND_PID=$!

# 等待後端啟動（偵測 port 8080）
log_info "等待後端啟動（最多 60 秒）..."
for i in $(seq 1 30); do
  if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    log_ok "後端已啟動 → http://localhost:8080"
    break
  fi
  # 檢查 process 是否意外結束
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    log_error "後端啟動失敗，請查看 backend.log："
    tail -30 "$SCRIPT_DIR/backend.log"
    cleanup
  fi
  if [ "$i" -eq 30 ]; then
    log_warn "後端可能還在啟動中，繼續等待... （查看 backend.log）"
  fi
  sleep 2
done

# ── Step 3：啟動前端 ──────────────────────────────────────────────────────────
log_info "Step 3/3  啟動前端（Vite dev server）..."
cd "$SCRIPT_DIR/frontend"
if [ ! -d "node_modules" ]; then
  log_info "未找到 node_modules，執行 npm install..."
  npm install
  log_ok "npm install 完成"
fi
npm run dev > "$SCRIPT_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!

sleep 2
if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
  log_error "前端啟動失敗，請查看 frontend.log"
  cleanup
fi
log_ok "前端已啟動 → http://localhost:5173"

# ── 完成 ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  開發環境啟動完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "  前端：${BLUE}http://localhost:5173${NC}"
echo -e "  後端：${BLUE}http://localhost:8080${NC}"
echo -e "  後端 log：${YELLOW}$SCRIPT_DIR/backend.log${NC}"
echo -e "  前端 log：${YELLOW}$SCRIPT_DIR/frontend.log${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "  按 ${RED}Ctrl+C${NC} 停止前後端"
echo ""

# 保持腳本運行，讓 trap 可以捕捉 Ctrl+C
wait
