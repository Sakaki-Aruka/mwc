#!/usr/bin/env bash
# MWC の受け入れ検証(合否判定)。ビルド→配置→起動→HTTP確認→停止を単一シェル内で一気通貫実行する。
# サンドボックスでは別 exec_command で起動したプロセスは消えるため、サーバーは必ずこのスクリプトの子プロセスとして持つ。
#
#   usage: scripts/verify.sh M0|M1|M2|M3|M4|M5|M6|M7|all [skip-build]
#
# 判定: PASS/FAIL を項目ごとに出力。1 項目でも FAIL なら exit 1。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p tmp
source scripts/run-test-server.sh

MILESTONE="${1:-all}"
SKIP_BUILD="${2:-0}"
PORT=8420
BASE_PATH="/"
BASE="http://127.0.0.1:$PORT"
CK="$ROOT/tmp/cookies.txt"
TMP_BODY="$ROOT/tmp/body.out"
TOKEN=""

FAILS=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILS=$((FAILS + 1)); }
info() { echo "INFO  $*"; }

# http METHOD PATH [data] [no-csrf]
# -> CODE=ステータス, BODY=レスポンス本文(ファイル $TMP_BODY)
http() {
  local method=$1 path=$2 data=${3:-} nocsr=${4:-0}
  local args=(-s --max-time 30 -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -c "$CK" -H "Content-Type: application/json")
  [[ $nocsr == 1 ]] || args+=(-H "X-MWC-Request: mwc")
  if [[ -n $data ]]; then args+=(--data "$data"); fi
  CODE=$(curl "${args[@]}" -X "$method" "$BASE$path" 2>"$ROOT/tmp/curl.err") || CODE="000"
  if [[ ! -f $TMP_BODY ]]; then : > "$TMP_BODY"; fi
  BODY=$(cat "$TMP_BODY")
}

json_get() { # json_get '<json>' '.a.b' -> jq
  jq -r "$2" <<<"$1" 2>/dev/null
}

# --- build ---
if [[ $SKIP_BUILD == 0 ]]; then
  if timeout 900 mvn -B -q package >"$ROOT/tmp/verify-mvn.log" 2>&1; then
    pass "mvn package"
  else
    fail "mvn package (see tmp/verify-mvn.log)"; tail -25 "$ROOT/tmp/verify-mvn.log" | sed 's/^/      /'
  fi
fi
if [[ -f $ROOT/target/MWC.jar ]]; then pass "target/MWC.jar exists"; else fail "target/MWC.jar missing"; fi

# --- deploy + start (base-path を引数で変更できる) ---
start_with() { # start_with <base-path>
  local bp=$1
  BASE_PATH=$bp
  BASE="http://127.0.0.1:$PORT$bp"
  rm -f "$CK" "$TMP_BODY"
  if [[ -d $TDIR/plugins/MWC ]]; then rm -rf "$TDIR/plugins/MWC"; fi  # 前回実行の設定・トークンを消す(初回起動の挙動を毎回検証)
  cp -f "$ROOT/target/MWC.jar" "$TDIR/plugins/MWC.jar"
  mwc_server_start
  if mwc_server_wait_ready 180; then
    pass "server started (Done detected, base-path $bp)"
  else
    fail "server startup (base-path $bp)"
    mwc_server_stop 2>/dev/null
    exit 1
  fi
  # トークンは初回起動時にコンソールへ一度だけ出力される(ユーザーの正規入手経路)
  TOKEN=$(grep -oE 'MWC token: [A-Za-z0-9._-]+' "$SLOG" | head -1 | awk '{print $3}')
}

stop_server() { mwc_server_stop 60; }

log_check() { # log_check <grep-pattern> <label> <expect: 1=must match, 0=must not match>
  local pat=$1 label=$2 expect=${3:-1}
  if grep -qE "$pat" "$SLOG"; then
    [[ $expect == 1 ]] && pass "log: $label" || fail "log: $label (unexpected: $(grep -m1 -E "$pat" "$SLOG" | cut -c1-120))"
  else
    [[ $expect == 1 ]] && fail "log: $label (pattern '$pat' not found)" || pass "log: $label (clean)"
  fi
}

# ================= milestones =================

m0_checks() {
  start_with "/"
  log_check 'Enabling MWC' "plugin enabled"
  log_check 'MWC .* enabled' "plugin onEnable log"
  log_check 'Exception|at online\.aruka\.mwc\.' "no exception/stacktrace" 0
  stop_server
}

m1_checks() {
  # --- run A: base-path / ---
  start_with "/"
  http GET "/api/status"
  [[ $CODE == 401 ]] && pass "status without auth -> 401" || fail "status without auth -> $CODE (want 401)"
  http POST "/api/login" '{"token":"wrong-token-1234567890"}'
  [[ $CODE == 401 ]] && pass "login with wrong token -> 401" || fail "login wrong token -> $CODE (want 401)"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] && pass "login with correct token -> 200" || fail "login correct token -> $CODE (want 200): $BODY"
  http GET "/api/status"
  if [[ $CODE == 200 ]]; then
    pass "status with auth -> 200"
    for field in tps memory uptime_seconds version players_online; do
      v=$(json_get "$BODY" ".$field")
      [[ -n $v && $v != null ]] && pass "status has $field ($v)" || fail "status missing $field"
    done
    tps0=$(json_get "$BODY" '.tps[0]')
    awk "BEGIN{exit !($tps0 > 0 && $tps0 <= 21)}" <<<"$tps0" 2>/dev/null && pass "tps[0] sane ($tps0)" || fail "tps[0] not sane: $tps0"
  else
    fail "status with auth -> $CODE (want 200): $BODY"
  fi
  # CSRF: state-changing without custom header -> 403
  http POST "/api/login" '{"token":"'"$TOKEN"'"}' 1
  [[ $CODE == 403 ]] && pass "POST without X-MWC-Request -> 403 (CSRF)" || fail "POST without X-MWC-Request -> $CODE (want 403)"
  http GET "/api/no-such-endpoint"
  [[ $CODE == 404 ]] && pass "unknown API path -> 404" || fail "unknown API path -> $CODE (want 404)"
  stop_server

  # --- run B: base-path /admin/mc ---
  start_with "/admin/mc"
  http GET "/api/status"
  [[ $CODE == 404 ]] && pass "base-path /admin/mc: /api/status -> 404" || fail "base-path /admin/mc: /api/status -> $CODE (want 404)"
  http GET "/admin/mc/api/status"
  [[ $CODE == 401 ]] && pass "base-path /admin/mc: /admin/mc/api/status unauth -> 401" || fail "base-path /admin/mc: /admin/mc/api/status unauth -> $CODE (want 401)"
  http POST "/admin/mc/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] && pass "base-path /admin/mc: login -> 200" || fail "base-path /admin/mc: login -> $CODE: $BODY"
  http GET "/admin/mc/api/status"
  if [[ $CODE == 200 ]]; then
    pass "base-path /admin/mc: /admin/mc/api/status -> 200"
  else
    fail "base-path /admin/mc: /admin/mc/api/status -> $CODE (want 200)"
  fi
  stop_server
}

m2_checks() {
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"
  http GET "/api/players"
  if [[ $CODE == 200 ]]; then
    pass "players -> 200"
    if jq -e 'type == "array"' <<<"$BODY" >/dev/null; then
      pass "players is JSON array"
    else
      fail "players is not a JSON array: $BODY"
    fi
    n=$(jq 'length' <<<"$BODY" 2>/dev/null || echo -1)
    [[ $n == 0 ]] && pass "0 players -> empty array" || fail "expected 0 players, got $n"
  else
    fail "players -> $CODE (want 200): $BODY"
  fi
  stop_server
}

m3_checks() {
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"
  # SSE: 15 秒間ストリームを読み、バッファ + ライブ配信を確かめる
  ( curl -s -N --max-time 15 -b "$CK" "$BASE/api/console" >"$ROOT/tmp/sse.out" 2>/dev/null & ) 
  sleep 2
  http POST "/api/command" '{"command":"say hello"}'
  if [[ $CODE == 200 ]]; then
    pass "command say hello -> 200"
  else
    fail "command say hello -> $CODE (want 200): $BODY"
  fi
  sleep 4
  if grep -q 'data: ' "$ROOT/tmp/sse.out" 2>/dev/null; then
    pass "SSE stream received data lines"
  else
    fail "SSE stream empty: $(head -c 200 "$ROOT/tmp/sse.out" 2>/dev/null)"
  fi
  if grep -q 'hello' "$ROOT/tmp/sse.out" 2>/dev/null; then
    pass "SSE contains 'say hello' output (live delivery)"
  else
    fail "SSE does not contain 'hello' (last: $(tail -c 300 "$ROOT/tmp/sse.out" 2>/dev/null))"
  fi
  sleep 8  # SSE の残り時間を待つ(curl 終了)
  log_check 'hello' "server log contains say output"
  log_check 'Exception|at online\.aruka\.mwc\.' "no exception/stacktrace" 0
  stop_server
}

m4_checks() {
  # 静的ページの相対パス検証(ブラウザは使えないため、参照する全パスを機械的に解決して確認)
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"
  http GET "/"
  if [[ $CODE == 200 ]] && grep -q '<html' "$TMP_BODY"; then
    pass "GET / -> 200 html"
  else
    fail "GET / -> $CODE (want 200 html)"
  fi
  if grep -E 'src="/|href="/|fetch\(["'"'"']/|EventSource\(["'"'"'/' "$TMP_BODY" >/dev/null 2>&1; then
    fail "index.html uses absolute paths (must be relative only)"
  else
    pass "index.html uses relative paths only"
  fi
  # 参照される静的ファイル/JS が相対解決で取れること
  for ref in $(grep -oE '(src|href)="[^"]+"' "$TMP_BODY" 2>/dev/null | sed -E 's/^(src|href)="//; s/"$//' | grep -v '^#' | grep -v '^http' | sort -u); do
    url=$(python3 - "$BASE/" "$ref" <<'EOF'
import sys, posixpath
base, ref = sys.argv[1], sys.argv[2]
print(posixpath.normpath(posixpath.join(base.rstrip('/'), ref)))
EOF
)
    c=$(curl -s -o /dev/null -w '%{http_code}' -b "$CK" --max-time 10 "$url")
    [[ $c == 200 ]] && pass "ref $ref -> 200" || fail "ref $ref -> $c (want 200)"
  done
  stop_server

  start_with "/admin/mc"
  http GET "/admin/mc/"
  if [[ $CODE == 200 ]] && grep -q '<html' "$TMP_BODY"; then
    pass "subpath: GET /admin/mc/ -> 200 html"
  else
    fail "subpath: GET /admin/mc/ -> $CODE (want 200 html)"
  fi
  for ref in $(grep -oE '(src|href)="[^"]+"' "$TMP_BODY" 2>/dev/null | sed -E 's/^(src|href)="//; s/"$//' | grep -v '^#' | grep -v '^http' | sort -u); do
    url=$(python3 - "$BASE/admin/mc/" "$ref" <<'EOF'
import sys, posixpath
base, ref = sys.argv[1], sys.argv[2]
print(posixpath.normpath(posixpath.join(base.rstrip('/'), ref)))
EOF
)
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url")
    [[ $c == 200 ]] && pass "subpath ref $ref -> 200" || fail "subpath ref $ref -> $c (want 200)"
  done
  stop_server
}

m5_checks() {
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"

  http GET "/api/files?path="
  if [[ $CODE == 200 ]] && jq -e 'type == "array"' <<<"$BODY" >/dev/null; then
    pass "files list root -> 200 array"
    jq -r '.[].name' <<<"$BODY" | grep -qx 'server.properties' && pass "list has server.properties" || fail "list root missing server.properties"
    jq -r '.[].name' <<<"$BODY" | grep -qx 'plugins' && pass "list has plugins" || fail "list root missing plugins"
  else
    fail "files list root -> $CODE: $BODY"
  fi
  http GET "/api/files?path=plugins"
  [[ $CODE == 200 ]] && jq -r '.[].name' <<<"$BODY" | grep -qx 'MWC.jar' && pass "list plugins has MWC.jar" || fail "list plugins -> $CODE"

  # パストラバーサル系は全部拒否
  for p in ".." "../test-server" "/etc/passwd" "..%2F..%2Fetc%2Fpasswd" "..%5Cwindows" "config/../../etc"; do
    http GET "/api/files?path=$p"
    case $CODE in 400|403|404) pass "traversal list '$p' -> $CODE (rejected)";; *) fail "traversal list '$p' -> $CODE (want 400/403/404): $BODY";; esac
  done

  http GET "/api/file?path=server.properties"
  if [[ $CODE == 200 ]] && grep -q 'level-seed' "$TMP_BODY"; then
    pass "read server.properties -> 200"
  else
    fail "read server.properties -> $CODE: $(head -c 100 "$TMP_BODY")"
  fi
  # 保護対象
  http GET "/api/file?path=plugins/MWC.jar"
  case $CODE in 403|404) pass "read protected MWC.jar rejected ($CODE)";; *) fail "read protected MWC.jar -> $CODE (want 403/404)";; esac
  http GET "/api/file?path=plugins/MWC/token.key"
  case $CODE in 403|404) pass "read protected token.key rejected ($CODE)";; *) fail "read protected token.key -> $CODE (want 403/404)";; esac

  # シンボリックリンクでルート外に出られない
  ln -sfn /etc "$TDIR/plugins/mwc-test-link"
  http GET "/api/file?path=plugins/mwc-test-link/passwd"
  case $CODE in 403|404) pass "symlink escape rejected ($CODE)";; *) fail "symlink escape -> $CODE (want 403/404)";; esac
  rm -f "$TDIR/plugins/mwc-test-link"

  # テキスト編集
  printf 'hello from mwc\n' >"$TDIR/config/mwc-edit-test.txt"
  ETAG=$(curl -s -D - -o /dev/null -b "$CK" --max-time 10 "$BASE/api/file?path=config/mwc-edit-test.txt" | tr -d '\r' | awk 'tolower($1)=="etag:"{gsub(/"/,"",$2); print $2}')
  http PUT "/api/file?path=config/mwc-edit-test.txt" "$(printf '{"content":"edited by mwc\\n","etag":"%s"}' "$ETAG")"
  [[ $CODE == 200 ]] && pass "edit text file -> 200" || fail "edit text file -> $CODE: $BODY"
  grep -qx 'edited by mwc' "$TDIR/config/mwc-edit-test.txt" && pass "file content updated" || fail "file content not updated: $(cat "$TDIR/config/mwc-edit-test.txt")"
  [[ -f $TDIR/config/mwc-edit-test.txt.bak ]] && pass ".bak created before save" || fail ".bak not created"

  # 競合: etag なし / 古い etag -> 409
  http PUT "/api/file?path=config/mwc-edit-test.txt" '{"content":"no etag\n"}'
  [[ $CODE == 409 ]] && pass "edit without etag -> 409" || fail "edit without etag -> $CODE (want 409)"
  http PUT "/api/file?path=config/mwc-edit-test.txt" '{"content":"stale\n","etag":"stale-etag-123"}'
  [[ $CODE == 409 ]] && pass "edit with stale etag -> 409" || fail "edit with stale etag -> $CODE (want 409)"

  # バイナリは編集不可
  printf 'BIN\x00\x01\x02' >"$TDIR/config/mwc-bin-test.bin"
  ET2=$(curl -s -D - -o /dev/null -b "$CK" --max-time 10 "$BASE/api/file?path=config/mwc-bin-test.bin" | tr -d '\r' | awk 'tolower($1)=="etag:"{gsub(/"/,"",$2); print $2}')
  http PUT "/api/file?path=config/mwc-bin-test.bin" "$(printf '{"content":"x\\n","etag":"%s"}' "$ET2")"
  case $CODE in 400|415) pass "edit binary rejected ($CODE)";; *) fail "edit binary -> $CODE (want 400/415)";; esac
  # バイナリの閲覧も不可
  http GET "/api/file?path=config/mwc-bin-test.bin"
  case $CODE in 400|415) pass "read binary rejected ($CODE)";; *) fail "read binary -> $CODE (want 400/415)";; esac

  # server.properties の最低検証
  http PUT "/api/file?path=server.properties" '{"content":"not key value\n=broken\n","etag":"stale"}'
  case $CODE in 400|409) pass "invalid server.properties rejected ($CODE)";; *) fail "invalid server.properties -> $CODE (want 400/409)";; esac
  # etag 正しめ + 不正内容
  ET3=$(curl -s -D - -o /dev/null -b "$CK" --max-time 10 "$BASE/api/file?path=server.properties" | tr -d '\r' | awk 'tolower($1)=="etag:"{gsub(/"/,"",$2); print $2}')
  http PUT "/api/file?path=server.properties" "$(printf '{"content":"=broken\\n","etag":"%s"}' "$ET3")"
  [[ $CODE == 400 ]] && pass "invalid server.properties (valid etag) -> 400" || fail "invalid server.properties -> $CODE (want 400): $BODY"

  # サイズ上限(編集 1MB 超)
  python3 -c "open('$TDIR/config/mwc-big-test.txt','w').write('x'*1100000)"
  ET4=$(curl -s -D - -o /dev/null -b "$CK" --max-time 10 "$BASE/api/file?path=config/mwc-big-test.txt" | tr -d '\r' | awk 'tolower($1)=="etag:"{gsub(/"/,"",$2); print $2}')
  http PUT "/api/file?path=config/mwc-big-test.txt" "$(python3 -c "import json;print(json.dumps({'content':'y'*1100000,'etag':'$ET4'}))")"
  case $CODE in 400|413) pass "oversize edit rejected ($CODE)";; *) fail "oversize edit -> $CODE (want 400/413)";; esac

  log_check 'MWC audit' "audit log entries"
  rm -f "$TDIR/config/mwc-edit-test.txt" "$TDIR/config/mwc-edit-test.txt.bak" "$TDIR/config/mwc-bin-test.bin" "$TDIR/config/mwc-big-test.txt"
  log_check 'Exception|at online\.aruka\.mwc\.' "no exception/stacktrace" 0
  stop_server
}

m6_checks() {
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"

  # アップロード(ボディ=ファイル内容)
  head -c 1000 /dev/urandom >"$ROOT/tmp/up-test.bin"
  SHA1=$(sha256sum "$ROOT/tmp/up-test.bin" | awk '{print $1}')
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 \
    --data-binary @"$ROOT/tmp/up-test.bin" -H "Content-Type: application/octet-stream" \
    "$BASE/api/files/upload?path=plugins/mwc-up-test.bin")
  BODY=$(cat "$TMP_BODY")
  [[ $CODE == 200 ]] && pass "upload new file -> 200" || fail "upload new file -> $CODE: $BODY"
  [[ -f $TDIR/plugins/mwc-up-test.bin ]] && [[ "$(sha256sum "$TDIR/plugins/mwc-up-test.bin" | awk '{print $1}')" == "$SHA1" ]] \
    && pass "uploaded content matches (sha256)" || fail "uploaded content mismatch/missing"

  # 上書き: 未指定なら 409
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 \
    --data-binary @"$ROOT/tmp/up-test.bin" "$BASE/api/files/upload?path=plugins/mwc-up-test.bin")
  BODY=$(cat "$TMP_BODY")
  [[ $CODE == 409 ]] && pass "overwrite without flag -> 409" || fail "overwrite without flag -> $CODE (want 409): $BODY"
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 \
    --data-binary @"$ROOT/tmp/up-test.bin" "$BASE/api/files/upload?path=plugins/mwc-up-test.bin&overwrite=true")
  BODY=$(cat "$TMP_BODY")
  [[ $CODE == 200 ]] && pass "overwrite=true -> 200" || fail "overwrite=true -> $CODE: $BODY"
  [[ -f $TDIR/plugins/mwc-up-test.bin.bak ]] && pass ".bak created on overwrite" || fail ".bak not created on overwrite"

  # ルート外・不正名
  for p in "../mwc-outside.txt" "plugins/../mwc-outside.txt" "/etc/mwc-evil.txt" "plugins%2F..%2Fx.txt" "plugins/..%5Cevil" "plugins/bad$(printf '\n')name"; do
    CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 \
      --data-binary @"$ROOT/tmp/up-test.bin" "$BASE/api/files/upload?path=$p" 2>/dev/null)
    BODY=$(cat "$TMP_BODY")
    case $CODE in 400|403|404) pass "upload bad path '$p' rejected ($CODE)";; *) fail "upload bad path '$p' -> $CODE (want 400/403/404): $BODY";; esac
  done
  # 保護対象へのアップロード
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 \
    --data-binary @"$ROOT/tmp/up-test.bin" "$BASE/api/files/upload?path=plugins/MWC/token.key&overwrite=true")
  case $CODE in 403|404) pass "upload to protected token.key rejected ($CODE)";; *) fail "upload protected -> $CODE (want 403/404)";; esac

  # 削除
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 -X DELETE \
    "$BASE/api/files?path=plugins/mwc-up-test.bin")
  BODY=$(cat "$TMP_BODY")
  [[ $CODE == 400 ]] && pass "delete without confirm -> 400" || fail "delete without confirm -> $CODE (want 400): $BODY"
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 -X DELETE \
    "$BASE/api/files?path=plugins/mwc-up-test.bin&confirm=true")
  BODY=$(cat "$TMP_BODY")
  [[ $CODE == 200 ]] && [[ ! -f $TDIR/plugins/mwc-up-test.bin ]] && pass "delete with confirm -> 200 and file gone" || fail "delete with confirm -> $CODE: $BODY"
  [[ ! -f $TDIR/plugins/mwc-up-test.bin.bak ]] || fail ".bak of deleted file still exists"

  # ディレクトリ削除(非空は拒否)
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 -X DELETE \
    "$BASE/api/files?path=plugins&confirm=true")
  case $CODE in 400|403|409) pass "delete non-empty dir rejected ($CODE)";; *) fail "delete non-empty dir -> $CODE (want 400/403/409)";; esac
  # 許可ルートそのものの削除
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 -X DELETE \
    "$BASE/api/files?path=config&confirm=true")
  case $CODE in 400|403) pass "delete the root itself rejected ($CODE)";; *) fail "delete root -> $CODE (want 400/403)";; esac
  # 保護対象の削除
  CODE=$(curl -s -o "$TMP_BODY" -w '%{http_code}' -b "$CK" -H "X-MWC-Request: mwc" --max-time 30 -X DELETE \
    "$BASE/api/files?path=plugins/MWC.jar&confirm=true")
  case $CODE in 403|404) pass "delete protected MWC.jar rejected ($CODE)";; *) fail "delete protected MWC.jar -> $CODE (want 403/404)";; esac

  log_check 'MWC audit' "audit log entries (upload/delete)"
  log_check 'Exception|at online\.aruka\.mwc\.' "no exception/stacktrace" 0
  stop_server
}

m7_checks() {
  start_with "/"
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] || fail "login failed: $CODE $BODY"
  http GET "/api/status"
  [[ $CODE == 200 ]] && pass "status before restart -> 200" || fail "status before restart -> $CODE"
  # 再起動(既定方式 shutdown: 監視側に再起動を任せる)
  http POST "/api/restart" '{"confirm":true}'
  [[ $CODE == 200 ]] && pass "restart API -> 200" || fail "restart API -> $CODE: $BODY"
  # サーバーが停止すること
  local i stopped=0
  for ((i = 0; i < 90; i++)); do
    kill -0 "$SERVER_PID" 2>/dev/null || { stopped=1; break; }
    sleep 1
  done
  [[ $stopped == 1 ]] && pass "server stopped after restart" || { fail "server did not stop within 90s"; mwc_server_stop; exit 1; }
  # 監視側(検証スクリプト)が再起動する
  sleep 2
  mwc_server_start
  mwc_server_wait_ready 180 && pass "monitor restarted server" || { fail "monitor restart failed"; exit 1; }
  # トークンは永続化されているはず(初回生成のまま)
  http POST "/api/login" '{"token":"'"$TOKEN"'"}'
  [[ $CODE == 200 ]] && pass "token survives restart (login ok)" || fail "login after restart -> $CODE: $BODY"
  http GET "/api/status"
  [[ $CODE == 200 ]] && pass "status after restart -> 200" || fail "status after restart -> $CODE: $BODY"
  log_check 'Exception|at online\.aruka\.mwc\.' "no exception/stacktrace" 0
  stop_server
}

# ================= dispatch =================

run_ms() {
  local ms=$1
  case $ms in
    M0) m0_checks ;;
    M1) m1_checks ;;
    M2) m2_checks ;;
    M3) m3_checks ;;
    M4) m4_checks ;;
    M5) m5_checks ;;
    M6) m6_checks ;;
    M7) m7_checks ;;
    *) fail "unknown milestone $ms";;
  esac
}

echo "=== verify $MILESTONE (server: $TDIR) ==="
case $MILESTONE in
  M0|M1|M2|M3|M4|M5|M6|M7) run_ms "$MILESTONE" ;;
  all) for ms in M0 M1 M2 M3 M4 M5 M6 M7; do run_ms $ms; done ;;
  *) echo "usage: $0 M0..M7|all [skip-build]"; exit 2 ;;
esac

echo
if [[ $FAILS -eq 0 ]]; then
  echo "RESULT: PASS (all checks)"
else
  echo "RESULT: FAIL ($FAILS failed checks)"
fi
exit $([[ $FAILS -eq 0 ]] && echo 0 || echo 1)
