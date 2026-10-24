# PLAN

## 現在のマイルストーン: M4 (最小フロントエンド)

**合格条件**: 状態・プレイヤー・コンソール(コマンド入力付き)がブラウザで動く。単一 HTML + JS で可。
**相対パスのみ**を使い、サブパスでも動く。
**機械判定**: `scripts/verify.sh M4` (m4_checks) = cycle 8 (本マイルストーン 1 回目)。
- base `/`: GET / → 200 html / index.html に絶対パスパターンなし
  (`src="/`・`href="/`・`fetch(["']/`・`EventSource(["']/`) / src・href の全参照を相対解決して 200
- base `/admin/mc`: GET /admin/mc/ → 200 html / 参照を `http://127.0.0.1:8420/admin/mc/` 基準で相対解決して 200
  (サブパス側は Cookie なしで判定 → 静的ファイルは認証不要で 200 であること)

### M4 タスク(1 つずつ commit)
- [x] M3 合格を状態ファイルに反映 + commit
- [ ] `web/index.html` + `web/app.js` + `web/app.css`:
  - ログインフォーム(POST api/login、X-MWC-Request 付き) → 200 でアプリ表示
  - 状態: api/status を 2 秒毎にポーリング( TPS 3 値・平均tick・メモリ・稼働時間・バージョン・人数)
  - プレイヤー: api/players をポーリング( name/uuid/ping/world)
  - コンソール: `EventSource('api/console')` でバッファ+ライブ表示、コマンド入力(POST api/command)
  - 全 API 呼び出しは相対パス `api/...`、静的参照は `static/...`(先頭スラッシュ禁止)
- [ ] handleRoot: base-path が非空のとき `{base}`(末尾スラッシュなし)→ `{base}/` を 301 でリダイレクト
  (ブラウザで末尾スラッシュなし URL に入力されても相対パスが壊れないように)
- [ ] ビルド → `timeout 1200 bash scripts/verify.sh M4` (cycle 8) → 結果をその場で eval/log.jsonl 追記
  → PASS なら PROGRESS 更新 + commit → M5

### M5/M6 への引き継ぎメモ
- 保護対象: token.key と自 jar のほかに **capture.log**(`console.capture-file` のキャプチャ行にはトークン表示行が含まれるため)。
- 静的ファイル(static/)は機密を含まないため認証不要(API は Cookie セッション必須)の設計は維持。

## 過去完了
- [x] M0 合格 (cycle 2, commit 2e36239)
- [x] M1 合格 (cycle 5, 24/24, commit cfa562a)
- [x] M2 合格 (cycle 6, commit df77243)
- [x] M3 合格 (cycle 7, 初回 PASS, commits a3040d9 / 59075c0 / 3edca36 / 0306f0e)

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [x] M3 コンソール (SSE + コマンド送信)
- [ ] M4 最小フロントエンド ← 現在
- [ ] M5 ファイル管理① 一覧・閲覧・テキスト編集
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
