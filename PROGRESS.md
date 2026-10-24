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

## M3 (2026-10-24): コンソール (SSE + コマンド送信) — 合格 (cycle 7、初回 PASS)
- 2026-10-24 21:30 監督役介入: 30 分以上コードを書かずに調査(45 コマンド)していたため作業停止。
  ツリーは M2 の commit(df77243)に戻され、AGENTS.md に追記が commit された(6a521b9)。
  **M3 は最初からやり直す**。新しい手順(最小 Appender → commit → リングバッファ → SSE → コマンド送信、
  1 つずつ足しては動かす)は PLAN.md に記載。jar 中身を開いての調査は禁止、調査は最初の 1 回のみ。
- 検証: `scripts/verify.sh M3` で PASS (cycle 7、初回)。
  - SSE (GET /api/console): 認証なし 401。認証ありで `data: ` 行がバッファ(起動ログ)から届く。
    接続後に `say hello` → SSE にライブ配信される
  - POST /api/command: `{"command":"say hello"}` → 200。サーバーログに say の出力と監査ログ
  - サーバーログに例外・スタックトレースなし
- 実装 (4 段階、各 commit):
  - step1 (a3040d9): log4j Appender (AbstractAppender サブクラス) をルートロガーに付与。
    2.17.1 の API 形はコンパイラ出力で確定 (append(LogEvent) / addAppender(Appender, Level, Filter) /
    removeAppender(String) / core.LoggerContext.configuration.rootLogger)
  - step2 (59075c0): リングバッファ (既定 1000 行、console.buffer-size) + ANSI 除去 (CSI/OSC/単一エスケープ)。
    検証: メッセージに ESC を含めて say → キャプチャに ESC なし・CSI 残骸 [32m/[0m ありで除去を確認
  - step3 (3edca36): SSE ハンドラ。購読登録とバッファ写しはアトミック。購読者ごとに有界キュー
    (満杯で古い行を捨てる)、10 秒 keepalive コメント
  - step4 (0306f0e): コマンド送信。MainThread.call で dispatchCommand(メインスレッド)。
    未知コマンドは 400 (dispatchCommand が false を返す。例外は投げない — 実測で判明)
- 副産物: `console.capture-file` 設定 (デバッグ用キャプチャ) は M5/M6 で保護対象 (トークン行を含む)

## M4 (2026-10-24): 最小フロントエンド — 合格 (cycle 9)
- 検証: `scripts/verify.sh M4` で PASS (cycle 9)。
  - base `/`: GET / → 200 html / index.html に絶対パスパターンなし / static/app.css・static/app.js が相対解決で 200
  - base `/admin/mc`: GET /admin/mc/ → 200 html / 参照を `/admin/mc/` 基準で相対解決して 200
- 実装 (commit eb54424): `web/index.html` + `web/app.js` + `web/app.css`(jar 内リソースとして配信)。
  - ログインフォーム(POST api/login) → 成功でアプリ表示。401 ならログイン画面に戻る
  - 状態: api/status を 2 秒毎ポーリング(TPS・avg tick・メモリ・稼働時間・バージョン・人数)
  - プレイヤー: api/players を 5 秒毎ポーリング(name/uuid/ping/world)
  - コンソール: `EventSource('api/console')` でバッファ+ライブ表示(表示上限 1000 行)、
    コマンド入力(POST api/command、X-MWC-Request 付き)
  - **全 API 呼び出しは相対パス** `api/...`(先頭スラッシュなし)、静的参照は `static/...`
  - 301 リダイレクト: 末尾スラッシュなしの `{base}` → `{base}/`(相対パスが親ディレクトリ基準で壊れるのを防ぐ)
- 失敗: cycle 8 (verify.sh 側の URL 構築バグ: サブパス側の参照 URL を `"$BASE/admin/mc/"` と書いていたが
  `BASE` は既に base-path を含むため二重プレフィックス `/admin/mc/admin/mc/static/...` になって 404)。
  実装ではなく検証スクリプトのバグ。`"$BASE/"` に修正して cycle 9 で PASS。

## M5 (2026-10-25): ファイル管理① 一覧・閲覧・テキスト編集 — 合格 (cycle 11)
- 検証: `scripts/verify.sh M5` で PASS (27/27、cycle 11)。
  - 一覧: GET api/files?path= → 200 array (server.properties・plugins)。plugins 配下に MWC.jar
  - traversal 6 種 (`..`・`../test-server`・`/etc/passwd`・`..%2F..%2Fetc%2Fpasswd`・`..%5Cwindows`・`config/../../etc`) → 400
  - 閲覧: server.properties 200。保護対象 (MWC.jar / token.key) 403。symlink (`plugins/mwc-test-link`→/etc) 経由 403
  - 編集: config/mwc-edit-test.txt 200 + 内容更新 + `.bak` 作成。etag なし/古い etag 409。
    バイナリの read/edit 415。server.properties 不正内容 400。1.1MB 編集 413
  - 監査ログ `MWC audit` あり、例外・スタックトレースなし
- 実装 (commit 6db11e0):
  - `FileAccess.kt`: パス検証共用。`normalizeRel` (URL decode 後 `\`・`:`・制御文字・先頭 `/`・`..` 拒否)、
    `resolve` (toRealPath + 許可ルート判定、外は SecurityEscape)、`isProtected` (実パス比較)、
    `isTextFile` (拡張子リスト + NUL 先頭なし)、`etag = mtime-size`
  - `FileListHandler` (GET api/files) / `FileReadHandler` (GET api/file、404→400→403→415→200+ETag) /
    `FileWriteHandler` (PUT api/file、404→400→403→415→413→409→400→.bak+原子リネーム+監査)
  - フロント: ファイルパネル (一覧・テキストエディタ、相対パス `api/...`、PUT は X-MWC-Request 付き)
- 失敗:
  - cycle 10: プラグイン有効化失敗 (NPE @ FileAccess.kt:24)。原因: `plugin.dataFolder` が実行時に
    **相対パス `plugins/MWC`** だったため `toPath().parent!!.parent!!` で plugins の parent が null。
    修正: 先に `toRealPath()` で絶対化してから親を辿る
  - verify.sh 側: `curl --data "$data"` は 1 引数 128KB (MAX_ARG_STRLEN) 制限で 1.1MB ボディが
    `Argument list too long` (E2BIG) → 000。`--data-binary @file` (一時ファイル経由) に修正
  - (後補) NPE 修正後の再実行 1 回 (26/27、E2BIG 起因) の eval 追記を漏れていた → cycle 8 の前例に従い
    cycle "10.5" として補足行を追加 (ts は当該実行のサーバーログ最終タイムスタンプ 01:19:38 から復元)

## M6 (ファイル管理②: アップロード・削除) — 合格 (cycle 12, commit 743679f)
- 検証: `timeout 1200 bash scripts/verify.sh M6` → **PASS (20/20 checks、初回)**
  - アップロード: 新ファイル 200 (sha256 一致) / 上書き無指定 409 / `overwrite=true` 200 + `.bak` 作成
  - 不正パス 6 種 (`..` 系・絶対パス・エンコード系・制御文字) 400 / 保護対象 `token.key` 403
  - 削除: confirm なし 400 / confirm あり 200 + ファイル消失 + **`.bak` も消失** /
    非空 dir 400 / 許可ルート自体 400 / 保護 `MWC.jar` 403
  - 監査ログ `MWC audit` あり、例外・スタックトレースなし
- 実装 (commit 743679f):
  - `FileUploadHandler.kt` (POST api/files/upload): rel を parent/name に分解 → 親を `FileAccess.resolve`
    → 保護/symlink 403 → 存在 && !overwrite 409 → ボディを `.mwc-up-*.tmp` にストリーム書き込み
    (> maxUploadSize で 413 + 一時ファイル削除) → overwrite なら `.bak` → ATOMIC_MOVE → audit
  - `FileDeleteHandler.kt` (DELETE api/files): confirm なし 400 → resolve (404/SecurityEscape 403) →
    保護 403 → 許可ルート自体 400 → ファイルは削除 + 兄弟 `<name>.bak` も削除 / dir は空のみ
    (`config.recursiveDelete` 時だけ再帰) → audit
  - フロント: 行ごとの削除ボタン (2 段階 confirm、X-MWC-Request)、アップロードボタン
    (file input → fetch で File をそのまま octet-stream POST、409 → 上書き確認 → overwrite=true 再送)
- 失敗: なし (初回 PASS)。eval 追記時に末尾改行欠如で結合が再発 → その場で python 修正
  (再発防止策を decisions-log に記録)

## M7 (再起動) — 合格 (cycle 14, commit 743b076)
- 検証: `timeout 1200 bash scripts/verify.sh M7` → **PASS (8/8 checks)**
  - `POST api/restart {"confirm":true}` → 200 (先に返却) → 90 秒以内にサーバー停止
  - 監視側 (verify.sh) が再起動 → ready / **同一トークン**で login 200 (token.key 永続化) /
    status 200 / 例外・スタックトレースなし
  - サブパスの素早い確認 (手動): `/api/restart` 404、`/admin/mc/api/restart`・`files/upload`・
    `files` が認証なし 401 (ルート存在)、login・index 200
- 実装 (commit 743b076):
  - `RestartHandler.kt` (POST api/restart): `confirm != true` → 400 → 監査ログ → 200 を先に出してから
    次のメインスレッド tick で停止。方式は設定 `restart.method` (既定 `shutdown` = `Bukkit.shutdown()`、
    再起動は外部監視に委任。`restart` は restart-script 方式を**反射**で呼び、使えなければ
    shutdown にフォールバック — SpigotServer は paper-api のコンパイルパスにないため)
  - フロント: 「サーバーを再起動」ボタン (confirm ダイアログ付き、送信後ボタン無効化 + 状態表示)
- 失敗:
  - cycle 13: コンパイルエラー 2 件 (`runTask` の SAM ラムダが Runnable/Consumer で曖昧 →
    明示 `Runnable { }`、`org.spigotmc.SpigotServer` がコンパイルパスにない → 反射へ変更)。
    verify は失敗した package の残りの旧 jar (M6 相当) で走り restart API が 404 → 実測 fail として記録

ALL MILESTONES PASSED

