# PLAN

## 現在のマイルストーン: M6 (ファイル管理②: アップロード・削除)

**合格条件** (5 章・ファイル管理要件のうち M6 分):
- アップロード: 種類は問わない(jar 含む)。許可ルート配下のみ。ファイル名検証(パス区切り・制御文字・先頭ドット連続を拒否)。
  既存ファイルの上書きは `overwrite=true` のみ(未指定なら 409)。上書き前に `.bak`。
  サイズ上限(設定)超は中断し一時ファイルを削除。ボディはストリームで書き出し
- 削除: `confirm=true` を API 側でも要求(なしは 400)。ディレクトリの再帰削除は既定で不可(空ディレクトリのみ)。
  許可ルートそのもの・保護対象は削除できない。削除されたファイルの `.bak` も一緒に片付ける(verify が確認する)
- 監査ログ: アップロード・削除にセッション・パス・サイズをサーバーログへ(`MWC audit`)
- ブラウザ UI からアップロード・削除(確認ダイアログ付き)できる(相対パスのみ)

**verify.sh m6_checks が確認する項目**(全文確認済み、base-path `/` の 1 回起動):
- `POST api/files/upload?path=plugins/mwc-up-test.bin`(ボディ=1KB ランダム、octet-stream) → 200、ディスク内容与 sha256 一致
- 上書き: `overwrite` 未指定 → 409 / `overwrite=true` → 200 + `.bak` 作成
- 不正パス 6 種 (`../mwc-outside.txt`・`plugins/../mwc-outside.txt`・`/etc/mwc-evil.txt`・`plugins%2F..%2Fx.txt`・`plugins/..%5Cevil`・`plugins/bad%0Aname`) → 400/403/404
- 保護対象へのアップロード (`plugins/MWC/token.key&overwrite=true`) → 403/404
- `DELETE api/files?path=plugins/mwc-up-test.bin` (confirm なし) → 400
- `DELETE ...&confirm=true` → 200 とファイル消失、**`.bak` も消えていること**
- 非空ディレクトリ削除 (`plugins&confirm=true`) → 400/403/409
- 許可ルートそのものの削除 (`config&confirm=true`) → 400/403
- 保護対象の削除 (`plugins/MWC.jar&confirm=true`) → 403/404
- 監査ログ `MWC audit` あり / 例外・スタックトレースなし

**実装方針**(M5 の `FileAccess` を再利用):
- upload: `normalizeRel` → 最後のセグメント=ファイル名、残りを親パス。親を `resolve`(実在+許可ルート内、symlink 経由の脱出は SecurityEscape)して、
  親実パス + 名前 でターゲットを作る。`isProtected` → 403。存在 && !overwrite → 409。
  一時ファイル(`.mwc-up-`)にストリーム書き込み(>maxUploadSize で 413+一時削除)→ rename。
  overwrite なら rename 前に `.bak`
- delete: `confirm` なし → 400 → `resolve` → 保護 → 403 → 許可ルート自体 → 400/403 →
  ファイルなら削除(+兄弟の `<name>.bak` があれば一緒に削除)→
  ディレクトリなら空のみ(`recursiveDelete` 既定 false。空でなければ 400)

### M6 タスク(1 つずつ commit)
- [ ] `FileUploadHandler`: POST api/files/upload?path=...[&overwrite=true](ストリーム一時ファイル+413+409+.bak+403)
- [ ] `FileDeleteHandler`: DELETE api/files?path=...&confirm=true(400 系+403+空ディレクトリのみ+.bak 片付け)
- [ ] `MwcPlugin` に登録 (POST files/upload, DELETE files)
- [ ] フロント: アップロードボタン(input[type=file] → fetch で File をそのまま POST、既存なら overwrite 確認)+ 削除ボタン(confirm ダイアログ)
- [ ] ビルド → `timeout 1200 bash scripts/verify.sh M6` → 結果をその場で eval 追記 → PASS なら PROGRESS 更新 + commit → M7

### M7 への引き継ぎメモ(verify.sh m7_checks 全文確認済み)
- `POST api/restart {"confirm":true}` → 200 を先に返し、メインスレッドで `Bukkit.shutdown()`(既定 `restartMethod=shutdown`)
- 90 秒以内にサーバー停止 → スクリプト側が `mwc_server_start` で再起動 → 180 秒で ready
- 再起動後に**同じトークン**で login 200 (token.key の永続化)/ status 200 / 例外なし

## 過去完了
- [x] M0 合格 (cycle 2, commit 2e36239)
- [x] M1 合格 (cycle 5, 24/24, commit cfa562a)
- [x] M2 合格 (cycle 6, commit df77243)
- [x] M3 合格 (cycle 7, 初回 PASS, commits a3040d9 / 59075c0 / 3edca36 / 0306f0e)
- [x] M4 合格 (cycle 9, commit eb54424。cycle 8 は verify.sh 側バグで fail)
- [x] M5 合格 (cycle 11, commit 6db11e0。cycle 10 は dataFolder 相対パス NPE + verify.sh の E2BIG で fail)

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [x] M3 コンソール (SSE + コマンド送信)
- [x] M4 最小フロントエンド
- [x] M5 ファイル管理① 一覧・閲覧・テキスト編集
- [ ] M6 ファイル管理② アップロード・削除 ← 現在
- [ ] M7 再起動
