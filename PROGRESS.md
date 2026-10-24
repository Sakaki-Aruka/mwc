# PROGRESS

## フェーズ0 (人間側)
- 2026-10-24: `scripts/check-env.sh --agent` 全項目 OK (java 21.0.12.1, maven 3.8.7, paper-api resolved, test-server 起動/停止 OK, git 設定済み)。ハーネス側項目はサンドボックス外で確認済み。

## M0 (2026-10-24): Maven + Kotlin 雛形 — 合格
- 検証: `scripts/verify.sh M0` で PASS (cycle 2)。
  - `mvn package` 成功 → `target/MWC.jar` (kotlin-stdlib shade 済み)
  - Paper 1.21.4-232 に配置しヘッドレス起動、`Enabling MWC`・onEnable ログを確認、例外なし、`stop` で正常停止
- 成果物: pom.xml (kotlin 2.0.21, jvmTarget 21, shade+relocation)、plugin.yml (api-version '1.21.4')、
  MwcPlugin 雛形、scripts/run-test-server.sh、scripts/verify.sh (M0..M7 対応、M1 以降は実装待ち)
- 失敗: cycle 1 で verify.sh 自体に M0 がサーバー起動しないバグ (実装ではない)。修正済み。

## M1 (2026-10-24): HTTP + base-path + 認証 + 状態 API — 合格 (cycle 5)
- 検証: `scripts/verify.sh M1` で PASS (24/24)。
  - base-path `/`: 認証なし 401 / 誤トークン 401 / 正解 200 / 認証あり status 200 (tps・avg_tick_time_ms・memory_used_mb・memory_max_mb・uptime_seconds・version=1.21.4・players_online・players_max がすべて妥当)
  - CSRF: POST (login 含む) で X-MWC-Request なしは 403。未知 API は 404
  - base-path `/admin/mc`: プレフィックスなし `/api/status` → 404、`/admin/mc/api/status` → 401→200
- 実装: com.sun.net.httpserver (context は base+/api と base のみ)、Auth (トークン 64hex 初回のみコンソール表示・token.key 0600・Cookie MWC_SESSION HttpOnly/SameSite=Strict/Path=base・レート制限 5回/60s→60s ブロック・CSRF ヘッダ X-MWC-Request)、StatusHandler (MainThread.call でメインスレッド取得)
- 失敗: cycle 3 (verify.sh の URL 構築バグ: 二重スラッシュ・BASE_RAW 未定義・フィールド名不一致)、cycle 4 (Auth.checkSession が MatchResult.value をセッションキーに使っていて常に 401 / verify.sh が base-path 設定を書き込んでいなかった)

## M2 (2026-10-24): プレイヤー API — 合格 (cycle 6、初回 PASS)
- 検証: `scripts/verify.sh M2` で PASS。GET /api/players → 200、JSON 配列、0 人のとき空配列。
- 実装: PlayersHandler (name/uuid/ping/world、MainThread.call)。`Player#getPing()` は Paper Javadoc 1.21.4 で確認済み (int, ms)。

## M3 (進行中・やり直し): コンソール (SSE + コマンド送信)
- 2026-10-24 21:30 監督役介入: 30 分以上コードを書かずに調査(45 コマンド)していたため作業停止。
  ツリーは M2 の commit(df77243)に戻され、AGENTS.md に追記が commit された(6a521b9)。
  **M3 は最初からやり直す**。新しい手順(最小 Appender → commit → リングバッファ → SSE → コマンド送信、
  1 つずつ足しては動かす)は PLAN.md に記載。jar 中身を開いての調査は禁止、調査は最初の 1 回のみ。
- [ ] ① 最小 Appender(行を受け取る) → ビルド → サーバーで確認 → commit
- [ ] ② リングバッファ (既定 1000 行、ANSI 除去)
- [ ] ③ GET api/console (SSE、バッファ送信 + リアルタイム配信)
- [ ] ④ POST api/command (メインスレッドで dispatchCommand) + onDisable で Appender 削除
- [ ] verify M3 (cycle 7 = 最初の verify 実行) + commit

