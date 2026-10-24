#!/usr/bin/env bash
# 検証用 Paper サーバー(test-server/)の操作ユーティリティ。
#
#   直接実行: scripts/run-test-server.sh start [--wait] | stop | status | loop
#     start      サーバーを起動し、終了するまでフォアグラウンドで待機
#     start --wait  起動して起動完了(Done)を検出したら終了
#     stop       実行中サーバーに 'stop' を送信して停止を待つ
#     status     pid・ログの表示
#     loop       起動→起動完了→終了を監視→2 秒後に再起動、の無限ループ(M7 の「監視側」)
#
#   source 時: mwc_server_start / mwc_server_wait_ready / mwc_server_stop / mwc_server_pid
#              を提供する。verify.sh は source して単一シェル内でサーバーを子プロセスとして
#              保持する(サンドボックスでは別コマンドで起動したプロセスは消えるため)。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TDIR="$ROOT/test-server"
JAR="$(ls "$TDIR"/paper-1.21.4-*.jar 2>/dev/null | head -1 || true)"
RUN="$TDIR/.mwc-run"
PIDF="$RUN/server.pid"
SLOG="$RUN/server.log"
STDINF="$RUN/stdin.fifo"
READY_PAT='Done ('
READY_TIMEOUT=${READY_TIMEOUT:-180}
STOP_TIMEOUT=${STOP_TIMEOUT:-60}

SERVER_PID=""

mwc_server_die() { echo "ERROR: $*" >&2; exit 1; }

mwc_server_check() {
  [[ -n "$JAR" ]] || mwc_server_die "paper-1.21.4-*.jar not found in $TDIR"
  mkdir -p "$RUN"
}

mwc_server_pid() {
  [[ -f $PIDF ]] || return 1
  local pid
  pid=$(cat "$PIDF")
  kill -0 "$pid" 2>/dev/null && echo "$pid"
}

mwc_server_start() {
  mwc_server_check
  if mwc_server_pid >/dev/null; then mwc_server_die "server already running (pid $(cat "$PIDF"))"; fi
  : > "$SLOG"
  rm -f "$STDINF"
  mkfifo "$STDINF"
  # shellcheck disable=SC2031
  ( cd "$TDIR" && exec java -Xms512M -Xmx2G -jar "$JAR" --nogui <"$STDINF" >>"$SLOG" 2>&1 ) &
  SERVER_PID=$!
  echo "$SERVER_PID" > "$PIDF"
  # 書き口を開けておく(閉じるとサーバーの stdin が EOF になる)
  exec 3>"$STDINF"
  echo "server pid $SERVER_PID (log: $SLOG)"
}

mwc_server_wait_ready() {
  local t=${1:-$READY_TIMEOUT} i
  for ((i = 0; i < t; i++)); do
    grep -q "$READY_PAT" "$SLOG" 2>/dev/null && return 0
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "server died during startup:"; tail -30 "$SLOG"
      return 1
    fi
    sleep 1
  done
  echo "server not ready after ${t}s:"; tail -30 "$SLOG"
  return 1
}

mwc_server_stop() {
  local pid t=${1:-$STOP_TIMEOUT} i
  pid=$(mwc_server_pid) || { echo "not running"; return 0; }
  # fd3 が閉じていたら(別シェルから stop した場合)書き口を再確保
  if ! { : >&3; } 2>/dev/null; then
    exec 3>"$STDINF"
  fi
  echo stop >&3
  for ((i = 0; i < t; i++)); do
    kill -0 "$pid" 2>/dev/null || { exec 3>&- 2>/dev/null || true; rm -f "$PIDF"; echo "stopped"; return 0; }
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  exec 3>&- 2>/dev/null || true
  rm -f "$PIDF"
  mwc_server_die "server did not stop within ${t}s; killed"
}

mwc_server_loop() {
  local n=0 rc
  while :; do
    n=$((n + 1))
    echo "=== mwc loop: start #$n ==="
    mwc_server_start
    mwc_server_wait_ready "$READY_TIMEOUT" || mwc_server_die "startup failed on loop #$n"
    wait "$SERVER_PID"
    rc=$?
    echo "=== mwc loop: server #$n exited rc=$rc; restarting in 2s ==="
    sleep 2
  done
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  cmd="${1:-help}"
  case "$cmd" in
    start)
      mwc_server_start
      if [[ "${2:-}" == "--wait" ]]; then
        mwc_server_wait_ready "${3:-$READY_TIMEOUT}" || exit 1
        exec 3>&- 2>/dev/null || true
      else
        wait "$SERVER_PID"
      fi
      ;;
    stop)  mwc_server_stop "${2:-$STOP_TIMEOUT}" ;;
    loop)  mwc_server_loop ;;
    status)
      if pid=$(mwc_server_pid); then echo "running (pid $pid)"; else echo "not running"; fi
      echo "log: $SLOG"
      ;;
    *) sed -n '2,16p' "${BASH_SOURCE[0]}" ;;
  esac
fi
