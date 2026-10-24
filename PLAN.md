# PLAN

## 現在のマイルストーン: M1 (HTTP サーバー + base-path + 認証 + 状態 API)

### M1 タスク
- [x] M0 完了 (2026-10-24): pom/kotlin 雛形・verify.sh・run-test-server.sh・有効化確認
- [x] MwcConfig.kt / Auth.kt / MwcHttp.kt / StatusHandler.kt / config.yml / index.html(プレースホルダ) 実装
- [x] verify.sh 修正 (cycle 3 失敗の要因): BASE 末尾スラッシュ除去+BASE_RAW 定義、m1 フィールド名を実 API に合わせる、m4 サブパス参照を "/" に、M6 の改行テストを %0A に、start_with で plugins/MWC/config.yml に base-path を書き込む
- [x] CSRF チェックを handleApi 先頭へ (login 含む全 POST/PUT/DELETE で 403)
- [x] Auth.checkSession のバグ修正: MatchResult.value (全文) をキーに使っていた → groupValues[1] (cycle 4 fail の原因)
- [x] verify M1 合格 (cycle 5、24/24 項目 PASS)
- [x] commit

### 次: M2 (プレイヤー API) — 合格
- [x] PlayersHandler: GET {bp}/api/players -> [{name, uuid, ping, world}] (0 人なら空配列)
- [x] verify M2 合格 (cycle 6、初回 PASS)
- [x] commit

### 現在のマイルストーン: M3 (コンソール: SSE + コマンド送信)
- [ ] LogBuffer: Log4j2 Appender をルートロガーに追加 → リングバッファ (既定 1000 行、ANSI 除去)。onDisable で Appender 削除
- [ ] ConsoleHandler: GET {bp}/api/console (SSE、Cookie 認証)。接続時にバッファ全量を送信 → 以降リアルタイム。keepalive
- [ ] CommandHandler: POST {bp}/api/command {command} -> メインスレッドで Bukkit.dispatchCommand(console, cmd)
- [ ] MwcPlugin で Appender の attach/detach
- [ ] verify M3 + commit

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [ ] M3 コンソール (SSE + コマンド送信) ← 現在
- [ ] M4 最小フロントエンド
- [ ] M5 ファイル管理① 一覧・閲覧・編集
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
