# 決定ログ (経緯・失敗の詳細)

## 2026-10-24
- `scripts/check-env.sh --agent` 実行: 全項目 OK。NG なし。
- Paper docs (plugin-yml) 確認: `api-version` の有効値は 1.13 - 26.3。1.20.5 以降はマイナー版がサポートされるため、1.21.4 には `api-version: '1.21.4'` を使う。
- paper-api 1.21.4-R0.1-SNAPSHOT の pom を確認: gson 2.11.0 / log4j-api 2.17.1 が compile スコープで宣言されており、サーバー実行時に必ず classpath にある (Gson・Log4j2 API は provided にしてよい)。
- 再起動方式の調査 (Javadoc `Server#restart()`: "If the server administrator has not configured restarting, the server will stop" + spigot.yml `restart-script` ドキュメント):
  `restart()` は spigot.yml の restart-script 設定時にスクリプト実行、未設定時は単純に停止。
  → 既定を `shutdown` にする (外部監視に委ねる方式が動作が予測可能で、M7 検証のループスクリプトとも整合)。`restart` も設定で選べる。
