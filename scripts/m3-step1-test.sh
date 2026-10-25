#!/usr/bin/env bash
# M3 ステップ1 検証: 最小 Appender がルートロガーの行を受け取っていることを確認。
# 起動 → 確認 → 停止 を単一シェル内で完結させる(サンドボックス制約のため)。
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p tmp
source scripts/run-test-server.sh

echo "== mvn package =="
if timeout 900 mvn -B -q package >tmp/verify-mvn.log 2>&1; then
  echo "PASS mvn package"
else
  echo "FAIL mvn package"; tail -30 tmp/verify-mvn.log; exit 1
fi
JAR=$(ls -t target/MWC-*.jar 2>/dev/null | head -1); [[ -n $JAR ]] || { echo "FAIL target/MWC-*.jar missing"; exit 1; }

echo "== deploy + start (capture-file = mwc-capture.log) =="
rm -rf "$TDIR/plugins/MWC"
mkdir -p "$TDIR/plugins/MWC"
cat >"$TDIR/plugins/MWC/config.yml" <<'EOF'
http:
  base-path: /
console:
  capture-file: mwc-capture.log
EOF
cp -f "$JAR" "$TDIR/plugins/MWC.jar"
rm -f "$TDIR/plugins/MWC/mwc-capture.log"
mwc_server_start
mwc_server_wait_ready 180 || { echo "FAIL server startup"; mwc_server_stop 2>/dev/null; exit 1; }
echo "PASS server started"

sleep 2
# コンソールに届く行(起動ログ以外)を 1 本送る
echo "mwc-step1-marker-$(date +%s)" >&3
sleep 3

CAP="$TDIR/plugins/MWC/mwc-capture.log"
if [[ -s $CAP ]]; then
  echo "PASS capture file exists ($(wc -l <"$CAP") lines)"
else
  echo "FAIL capture file missing/empty: $CAP"
fi
grep -q 'mwc-step1-marker' "$CAP" 2>/dev/null && echo "PASS marker line captured" || echo "FAIL marker not in capture"
grep -qE 'Exception|at online\.aruka\.mwc\.' "$SLOG" && { echo "FAIL server log has exception"; grep -m2 -E 'Exception|at online\.aruka\.mwc\.' "$SLOG"; } || echo "PASS no exception in server log"
grep -q 'console capture active' "$SLOG" && echo "PASS appender attached (server log)" || echo "FAIL no 'console capture active' line"
echo "--- first 5 lines of capture ---"
head -5 "$CAP" 2>/dev/null | cut -c1-150
echo "--- stop ---"
mwc_server_stop 60
