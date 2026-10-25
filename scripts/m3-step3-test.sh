#!/usr/bin/env bash
# M3 ステップ3 検証: SSE (GET api/console)。
#  1) 認証なしは 401
#  2) 認証ありで text/event-stream が 200、バッファ行 (起動ログ) が data: で届く
#  3) 接続中に say した行がライブ配信される
#  4) 例外が無い
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
CK=tmp/ck-step3.txt

# 1) 認証なし 401
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/console")
[[ $CODE == 401 ]] && echo "PASS unauthenticated console -> 401" || echo "FAIL unauthenticated console -> $CODE (want 401)"

# 2) ログイン (Cookie 取得)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$CK" -H 'X-MWC-Request: mwc' -d "{\"token\":\"$TOKEN\"}" "$BASE/api/login")
[[ $CODE == 200 ]] && echo "PASS login 200" || { echo "FAIL login -> $CODE"; mwc_server_stop 60; exit 1; }

# 3) SSE 接続をバックグラウンドで開始 → バッファ + ライブ配信を確認
rm -f tmp/sse.out
( curl -s -N -b "$CK" "$BASE/api/console" > tmp/sse.out 2>/dev/null & echo $! > tmp/sse.pid )
sleep 2
head -c 200 tmp/sse.out
echo
if grep -q 'data: ' tmp/sse.out; then
  echo "PASS SSE received data lines (buffer)"
else
  echo "FAIL SSE no data lines: $(head -c 200 tmp/sse.out)"
fi
if grep -q 'MWC 0.1.0 enabled' tmp/sse.out; then
  echo "PASS SSE buffer contains startup line"
else
  echo "FAIL SSE buffer missing startup line"
fi

# 4) ライブ配信: say を送信し SSE に届くことを確認
echo "mwc-step3-live" >&3
for i in $(seq 1 10); do
  grep -q 'mwc-step3-live' tmp/sse.out && break
  sleep 1
done
if grep -q 'mwc-step3-live' tmp/sse.out; then
  echo "PASS SSE live delivery (line appeared after connect)"
else
  echo "FAIL SSE live delivery (marker not in stream)"
fi

# 5) 例外が無い
grep -qE 'Exception|at online\.aruka\.mwc\.' "$SLOG" && { echo "FAIL server log has exception"; grep -m2 -E 'Exception|at online\.aruka\.mwc\.' "$SLOG"; } || echo "PASS no exception in server log"
echo "--- stop ---"
kill "$(cat tmp/sse.pid 2>/dev/null)" 2>/dev/null || true
mwc_server_stop 60
