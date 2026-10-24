# PLAN

## 現在のマイルストーン: M5 (ファイル管理①: 一覧・閲覧・テキスト編集)

**合格条件** (5 章・ファイル管理要件のうち M5 分):
- 一覧: GET {bp}/api/files?path=... → 配列(名前・種別・サイズ・更新日時)
- 閲覧: テキストファイルの内容取得。バイナリ(jar 等)は閲覧・編集不可
- テキスト編集: 拡張子許可リストかつ NUL なしかつ UTF-8 で読めること、サイズ上限(既定 1MB)、
  保存前に `.bak` 作成、一時ファイル→リネームで原子的書き込み、
  競合対策(読み込み時の mtime を保存時に送り、変更されていたら 409)、
  server.properties は `key=value` 形式の最低限の検証
- パストラバーサル等拒否(`..`・絶対パス・%2F・\、シンボリックリンクでルート外に出ない)
- 保護対象(閲覧・編集・削除不可): `plugins/MWC/token.key`、自 jar `plugins/MWC.jar`、capture.log
- ブラウザ UI(ファイル一覧・テキストエディタ)からも操作できる(相対パスのみ)

**許可ルート**: `file-manager.roots`(既定: server.properties / bukkit.yml / spigot.yml / config / plugins)。
normalize 後のパスが許可ルート配下であることを全操作で検証する。

**verify.sh M5 が確認する項目**(先に確認済み):
- list root → 200 array で server.properties と plugins が含まれる / list plugins に MWC.jar
- traversal(`..`・`/etc/passwd`・`..%2F..`・`..%5C`・`config/../../etc`) → 400/403/404
- read server.properties → 200 / read 保護対象(MWC.jar・token.key) → 403/404
- (以下 285 行以降: 編集・409 系) — 実装前に verify.sh m5_checks 全文を確認すること

### M5 タスク(1 つずつ commit)
- [ ] `FileAccess.kt`: ルート解決・正規化・許可判定・保護対象判定(全ハンドラ共用)
- [ ] `FileListHandler`: GET api/files?path= → ディレクトリ一覧 JSON
- [ ] `FileReadHandler`: GET api/file?path= → テキストのみ(バイナリ 415 相当)、保護対象 403
- [ ] `FileWriteHandler`: PUT api/file?path= {content, mtime} → バックアップ+原子的書き込み+競合 409+properties 検証
- [ ] フロント: ファイル一覧+エディタを追加(相対パス)
- [ ] ビルド → `timeout 1200 bash scripts/verify.sh M5` → 結果をその場で eval 追記 → PASS なら PROGRESS 更新 + commit → M6

### M6 への引き継ぎメモ
- アップロード: PUT {bp}/api/files?path=...(octet-stream ボディ)、overwrite=true なしで既存なら 409、.bak 作成
- 削除: DELETE api/file?path=&confirm=true、空ディレクトリのみ(再帰は設定で)、許可ルート自体・保護対象は不可
- 監査ログ: アップロード・削除・編集にセッション・パス・サイズをサーバーログへ
- 保護対象: token.key / 自 jar / capture.log

## 過去完了
- [x] M0 合格 (cycle 2, commit 2e36239)
- [x] M1 合格 (cycle 5, 24/24, commit cfa562a)
- [x] M2 合格 (cycle 6, commit df77243)
- [x] M3 合格 (cycle 7, 初回 PASS, commits a3040d9 / 59075c0 / 3edca36 / 0306f0e)
- [x] M4 合格 (cycle 9, commit eb54424。cycle 8 は verify.sh 側バグで fail)

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [x] M3 コンソール (SSE + コマンド送信)
- [x] M4 最小フロントエンド
- [ ] M5 ファイル管理① 一覧・閲覧・テキスト編集 ← 現在
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
