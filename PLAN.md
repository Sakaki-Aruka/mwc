# PLAN

## 現在のマイルストーン: M1 (HTTP サーバー + base-path + 認証 + 状態 API)

### M1 タスク
- [x] M0 完了 (2026-10-24): pom/kotlin 雛形・verify.sh・run-test-server.sh・有効化確認
- [ ] Http.kt: com.sun.net.httpserver 起動。context は base-path 配下 1 つのみ登録。
      - base-path `/` のとき context `/`、それ以外は context `"$bp/api"` + 静的/ルート用 context `$bp`
      - リクエストはすべて base-path 配下のみ (base 外は 404 になること: context がマッチしないため)
- [ ] Auth.kt: トークン生成 (SecureRandom 32byte base64url) + token.key 永続化 (plugins/MWC/)
      - 初回生成時はコンソールに一度だけ表示
      - POST {bp}/api/login {token} -> HttpOnly/SameSite=Strict Cookie (Path=bp)
      - Cookie セッションの検証 (メモリ map: cookieToken -> 有効期限)
      - レート制限: IP ごとの失敗カウント (5 回で 60 秒ブロック)
      - CSRF: 状態変更メソッド (POST/PUT/DELETE) はヘッダ X-MWC-Request: mwc 必須
- [ ] StatusHandler: GET {bp}/api/status -> tps[]/avg_tick_time_ms/memory/uptime_seconds/version/players_online/players_max
      (Bukkit API はメインスレッドで取得: scheduler + CountDownLatch)
- [ ] MwcPlugin: onEnable で設定読込・トークン生成・HTTP 起動。onDisable で停止
- [ ] verify M1 (base-path / と /admin/mc の 2 回起動) で合格
- [ ] commit

### 次: M2 (プレイヤー API)
- [ ] PlayersHandler: GET {bp}/api/players -> [{name, uuid, ping, world}] (0 人なら空配列)
- [ ] verify M2 + commit

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [ ] M1 HTTP + base-path + 認証 + 状態 API ← 現在
- [ ] M2 プレイヤー API
- [ ] M3 コンソール (SSE + コマンド送信)
- [ ] M4 最小フロントエンド
- [ ] M5 ファイル管理① 一覧・閲覧・編集
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
