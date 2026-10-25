#!/usr/bin/env bash
# M3 ステップ4 検証: コマンド送信 (POST api/command)。
#  1) say hello が 200 で、サーバーログに出力と監査ログが出る
#  2) 未知コマンドが 400 (CommandException)
#  3) 例外 / スタックトレースが無い
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

echo "== deploy + start =="
rm -rf "$TDIR/plugins/MWC"
mkdir -p "$TDIR/plugins/MWC"
cat >"$TDIR/plugins/MWC/config.yml" <<'EOF'
http:
  base-path: /
EOF
cp -f "$JAR" "$TDIR/plugins/MWC.jar"
mwc_server_start
mwc_server_wait_ready 180 || { echo "FAIL server startup"; mwc_server_stop 2>/dev/null; exit 1; }
echo "PASS server started"
TOKEN=$(grep -oP 'MWC token: \K[0-9a-f]{64}' "$SLOG" | head -1)
[[ -n $TOKEN ]] || { echo "FAIL no token in log"; mwc_server_stop 60; exit 1; }
BASE=http://127.0.0.1:8420
CK=tmp/ck-step4.txt
CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$CK" -H 'X-MWC-Request: mwc' -d "{\"token\":\"$TOKEN\"}" "$BASE/api/login")
[[ $CODE == 200 ]] && echo "PASS login 200" || { echo "FAIL login -> $CODE"; mwc_server_stop 60; exit 1; }

# 1) say hello
CODE=$(curl -s -o tmp/body1.json -w '%{http_code}' -b "$CK" -H 'X-MWC-Request: mwc' -d '{"command":"say hello"}' "$BASE/api/command")
[[ $CODE == 200 ]] && echo "PASS say hello -> 200" || { echo "FAIL say hello -> $CODE: $(cat tmp/body1.json)"; }
sleep 2
if grep -q 'hello' "$SLOG" && grep -q 'MWC audit: command sent: say hello' "$SLOG"; then
  echo "PASS server log has say output + audit line"
else
  echo "FAIL server log missing say output or audit line"
fi

# 2) 未知コマンド → 400
CODE=$(curl -s -o tmp/body2.json -w '%{http_code}' -b "$CK" -X POST -H 'X-MWC-Request: mwc' -d '{"command":"definitelynotacommandxyz"}' "$BASE/api/command")
[[ $CODE == 400 ]] && echo "PASS unknown command -> 400 ($(head -c 120 tmp/body2.json))" || { echo "FAIL unknown command -> $CODE: $(cat tmp/body2.json)"; }

# 3) 例外 / スタックトレースが無い
grep -qE 'Exception|at online\.aruka\.mwc\.' "$SLOG" && { echo "FAIL server log has exception"; grep -m2 -E 'Exception|at online\.aruka\.mwc\.' "$SLOG"; } || echo "PASS no exception in server log"
echo "--- stop ---"
mwc_server_stop 60
