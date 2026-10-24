#!/usr/bin/env bash
# フェーズ0の完了判定。項目ごとに OK / NG を出す。環境変数の値は出力しない。
#   usage: scripts/check-env.sh [--agent]
#          --agent  エージェント(サンドボックス内)用。ハーネス側の項目(キー・LLM・Codex・MCP)は
#                   実行スクリプトがサンドボックスの外で確認済みなので飛ばす
#   env:   LLM_BASE_URL   既定 http://127.0.0.1:8080
#          MWC_SECRETS    検索 API キーを置いた env ファイル(既定 ~/.config/mwc-harness/secrets.env)
#          SKIP_SERVER=1  検証サーバーの起動確認を飛ばす
set -u
cd "$(dirname "$0")/.."
ROOT=$PWD
LLM_BASE_URL=${LLM_BASE_URL:-http://127.0.0.1:8080}
MWC_SECRETS=${MWC_SECRETS:-$HOME/.config/mwc-harness/secrets.env}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

AGENT=0
[[ ${1:-} == --agent ]] && AGENT=1
skip() { printf -- '--  %-18s %s\n' "$1" "checked by the harness runner (--agent)"; }

fails=0
ok() { printf 'OK  %-18s %s\n' "$1" "$2"; }
ng() { printf 'NG  %-18s %s\n' "$1" "$2"; fails=$((fails + 1)); }

# --- Java / Maven ---
jver=$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
jmajor=${jver%%.*}
if [[ -n $jver && $jmajor -ge 21 ]]; then ok java "$jver (>= 21)"; else ng java "found '${jver:-none}', need >= 21"; fi
if mvn -v >/dev/null 2>&1; then ok maven "$(mvn -v 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | head -1)"; else ng maven "mvn -v failed"; fi

# --- 依存解決 ---
if mvn -B -q dependency:get -Dartifact=io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT \
    -DremoteRepositories=papermc::default::https://repo.papermc.io/repository/maven-public/ >"$WORK/mvn.log" 2>&1; then
  ok paper-api "io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT resolved"
else
  ng paper-api "resolve failed (last lines below)"; tail -5 "$WORK/mvn.log" | sed 's/^/    /'
fi

# --- 検証サーバー ---
check_server() {
  local dir=$ROOT/test-server jar
  jar=$(ls "$dir"/paper-1.21.4-*.jar 2>/dev/null | head -1)
  [[ -n $jar ]] || { ng test-server "no paper-1.21.4-*.jar in test-server/"; return; }
  grep -qx 'eula=true' "$dir/eula.txt" 2>/dev/null || { ng test-server "eula.txt is not eula=true"; return; }
  if ss -ltn 2>/dev/null | grep -q ':25565 '; then ng test-server "port 25565 already in use"; return; fi
  local fifo=$WORK/stdin log=$WORK/server.log pid i
  mkfifo "$fifo"
  (cd "$dir" && exec java -Xms512M -Xmx2G -jar "$jar" --nogui <"$fifo" >"$log" 2>&1) &
  pid=$!
  exec 3>"$fifo"
  for ((i = 0; i < 180; i++)); do
    grep -q 'Done (' "$log" && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if ! grep -q 'Done (' "$log"; then
    exec 3>&-; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    ng test-server "startup not detected within 180s"; tail -5 "$log" | sed 's/^/    /'; return
  fi
  echo stop >&3
  for ((i = 0; i < 60; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  exec 3>&-
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"; wait "$pid" 2>/dev/null; ng test-server "did not stop within 60s after 'stop'"; return
  fi
  wait "$pid"; local rc=$?
  if [[ $rc -eq 0 ]]; then ok test-server "$(basename "$jar"): started ($(grep -o 'Done ([^)]*)' "$log")) and stopped"
  else ng test-server "exit code $rc after stop"; fi
}
if [[ ${SKIP_SERVER:-0} == 1 ]]; then echo "--  test-server        skipped (SKIP_SERVER=1)"; else check_server; fi

if [[ $AGENT == 1 ]]; then
  for k in brave-key llm-models tool-call harness sandbox codex-bridge mcp-search mcp-fs; do skip $k; done
else
# --- 検索 API キー(値は読まない・出さない) ---
key_state() {  # $1=name -> env / file / missing
  if [[ -n ${!1:-} ]]; then echo env
  elif [[ -f $MWC_SECRETS ]] && grep -Eq "^$1=.+" "$MWC_SECRETS"; then echo file
  else echo missing; fi
}
if [[ -f $MWC_SECRETS ]]; then
  perm=$(stat -c %a "$MWC_SECRETS")
  [[ $perm == 600 ]] || ng secrets-perm "$MWC_SECRETS is $perm (need 600)"
fi
case $(key_state BRAVE_API_KEY) in
  env) ok brave-key "set (environment)" ;;
  file) ok brave-key "set ($MWC_SECRETS)" ;;
  *) ng brave-key "BRAVE_API_KEY not set (environment or $MWC_SECRETS)" ;;
esac
case $(key_state TAVILY_API_KEY) in
  missing) echo "--  tavily-key         not set (optional)" ;;
  *) ok tavily-key "set" ;;
esac
[[ -e $ROOT/.env ]] && ng env-location ".env exists in the work directory; keep it outside mwc/"

# --- LLM エンドポイント ---
code=$(curl -s -m 10 -o "$WORK/models.json" -w '%{http_code}' "$LLM_BASE_URL/v1/models")
if [[ $code == 200 ]]; then
  model=$(jq -r '.data[0].id // empty' "$WORK/models.json")
  ok llm-models "GET /v1/models 200 (model: ${model:-?})"
else
  ng llm-models "GET $LLM_BASE_URL/v1/models -> ${code:-no response}"
fi

# --- tool call スモーク ---
if [[ $code == 200 ]]; then
  jq -n --arg m "$model" '{
    model: $m, max_tokens: 4096,
    messages: [{role: "user", content: "東京の天気を get_weather ツールで調べて。"}],
    tools: [{type: "function", function: {name: "get_weather", description: "Get current weather for a city",
      parameters: {type: "object", properties: {city: {type: "string"}}, required: ["city"]}}}]
  }' >"$WORK/req.json"
  code2=$(curl -s -m 600 -o "$WORK/resp.json" -w '%{http_code}' -H 'Content-Type: application/json' \
    -d @"$WORK/req.json" "$LLM_BASE_URL/v1/chat/completions")
  name=$(jq -r '.choices[0].message.tool_calls[0].function.name // empty' "$WORK/resp.json" 2>/dev/null)
  args=$(jq -r '.choices[0].message.tool_calls[0].function.arguments // empty' "$WORK/resp.json" 2>/dev/null)
  if [[ $code2 == 200 && $name == get_weather ]] && jq -e '.city | type == "string" and length > 0' <<<"$args" >/dev/null 2>&1; then
    ok tool-call "get_weather($args)"
  else
    ng tool-call "http=$code2 name='${name}' args='${args:0:120}'"
  fi
else
  ng tool-call "skipped (endpoint unreachable)"
fi

# --- ハーネス(Codex CLI) ---
CODEX=${CODEX:-$HOME/.local/bin/codex}
if cver=$("$CODEX" --version 2>/dev/null); then
  ok harness "$cver"
  bwrap=$(dirname "$(readlink -f "$CODEX")")/codex-resources/bwrap
  if [[ ! -x $bwrap ]]; then ng sandbox "bundled bwrap not found ($bwrap)"
  elif "$bwrap" --ro-bind / / --unshare-user --unshare-net true 2>"$WORK/bwrap.log"; then ok sandbox "bwrap can create user namespaces"
  else ng sandbox "bwrap failed: $(head -1 "$WORK/bwrap.log") (AppArmor userns profile needed)"; fi
else
  ng harness "codex not found ($CODEX)"
fi
BRIDGE_URL=${BRIDGE_URL:-http://127.0.0.1:8081}
bcode=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$BRIDGE_URL/v1/models")
if [[ $bcode == 200 ]]; then ok codex-bridge "$BRIDGE_URL -> llama-server"
else ng codex-bridge "GET $BRIDGE_URL/v1/models -> ${bcode:-no response} (start: tmux new -d -s bridge ~/.local/bin/codex-bridge)"; fi
for srv in "mcp-search" "mcp-fs -root $ROOT"; do
  bin=${srv%% *}
  tools=$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' |
    timeout 10 "$HOME/.local/bin/"$srv 2>/dev/null | jq -r 'select(.id==2) | [.result.tools[].name] | join(",")')
  if [[ -n $tools ]]; then ok "$bin" "$tools"; else ng "$bin" "no tools/list response"; fi
done

fi

# --- git ---
if command -v git >/dev/null; then
  gn=$(git config user.name); ge=$(git config user.email)
  if [[ -n $gn && -n $ge ]]; then ok git "user.name / user.email set"
  else ng git "user.name $([[ -n $gn ]] && echo set || echo unset), user.email $([[ -n $ge ]] && echo set || echo unset)"; fi
else
  ng git "git not found"
fi

echo
if [[ $fails -eq 0 ]]; then echo "フェーズ0: 全項目 OK"; else echo "フェーズ0: NG $fails 件"; exit 1; fi
