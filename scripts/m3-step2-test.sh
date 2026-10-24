#!/usr/bin/env bash
# M3 ステップ2 検証: リングバッファ + ANSI 除去。
# 色付き say をコンソールに送り、サーバー自身ログ(SLOG)に ESC バイトが残るのに対し
# MWC キャプチャには ESC バイトが無く除去済みのテキストがあることを確かめる。
# (リングバッファ本体はステップ3 の SSE で初期送信として確認する)
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
[[ -f target/MWC.jar ]] || { echo "FAIL target/MWC.jar missing"; exit 1; }

echo "== deploy + start (capture-file = mwc-capture.log) =="
rm -rf "$TDIR/plugins/MWC"
mkdir -p "$TDIR/plugins/MWC"
cat >"$TDIR/plugins/MWC/config.yml" <<'EOF'
http:
  base-path: /
console:
  capture-file: mwc-capture.log
EOF
cp -f target/MWC.jar "$TDIR/plugins/MWC.jar"
rm -f "$TDIR/plugins/MWC/mwc-capture.log"
mwc_server_start
mwc_server_wait_ready 180 || { echo "FAIL server startup"; mwc_server_stop 2>/dev/null; exit 1; }
echo "PASS server started"

sleep 2
# メッセージ本文に ANSI (ESC バイト) を含めて say を送信。
# コンソールログ行はそのままのバイトで出るので、Appender が ESC を受け取っていることを確認できる。
printf 'say \x1b[32mANSI test\x1b[0m\n' >&3
sleep 3

CAP="$TDIR/plugins/MWC/mwc-capture.log"
if [[ -s $CAP ]]; then
  echo "PASS capture file exists ($(wc -l <"$CAP") lines)"
else
  echo "FAIL capture file missing/empty: $CAP"; mwc_server_stop 60; exit 1
fi

# 1) CAP の該当行に ESC が無いのに CSI の残骸 [32m / [0m が残っている
#    → Appender が ESC バイトを受け取り、除去したことを示す直接的な証拠
#    (SLOG は Paper 側のコンソール appender が既に ANSI 除去済みのため ESC 無し)
if grep 'ANSI test' "$CAP" | grep -qE '\[32m.*\[0m' && ! grep -qP '\x1b' "$CAP"; then
  echo "PASS capture shows stripped CSI (ESC removed, [32m/[0m residue)"
else
  echo "FAIL no CSI residue in capture (cannot prove ESC was received & stripped)"
fi

# 2) MWC キャプチャには ESC バイトが無い (ANSI 除去された)
if grep -qP '\x1b' "$CAP"; then
  echo "FAIL capture contains ESC bytes"; grep -m1 -nP '\x1b' "$CAP" | head -1 | cut -c1-120
else
  echo "PASS capture has no ESC bytes"
fi

# 3) 除去済みのテキストがキャプチャに残っている
grep -q 'ANSI test' "$CAP" && echo "PASS stripped text present" || echo "FAIL stripped text missing"

# 4) 例外が無い
grep -qE 'Exception|at online\.aruka\.mwc\.' "$SLOG" && { echo "FAIL server log has exception"; grep -m2 -E 'Exception|at online\.aruka\.mwc\.' "$SLOG"; } || echo "PASS no exception in server log"
echo "--- ANSI test lines in capture ---"
grep 'ANSI test' "$CAP" | cut -c1-150
echo "--- stop ---"
mwc_server_stop 60
