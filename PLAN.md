# PLAN

## 現在のマイルストーン: M3 (コンソール: SSE + コマンド送信) — 最初からやり直し(監督役介入 2026-10-24 21:30)

**介入の経緯**: M3 着手後 30 分以上、コードを書かず調査(`~/.m2` の jar 中身確認等)に費やしていたため、
監督役が作業を止め、ツリーを M2 の commit(df77243)に戻し、AGENTS.md に追記を commit(6a521b9)。
**新しいルール(遵守)**:
- 調べ物は最初の 1 回だけ(公式 Javadoc を fetch_url で 1〜2 ページ)。
- `~/.m2` やサーバー jar(Javadoc jar 含む)を unzip/jar/python zipfile で開かない。
- 1 疑問あたり web_search + fetch_url 合計 3 回まで。決まらなければ書いてビルドして確かめる。
- 手順: ①最小コード(Appender をルートロガーに付与し行を受け取る)→ビルド→検証サーバーで確認→commit
  ②リングバッファ → ③SSE → ④コマンド送信、の順に 1 つずつ足してはビルド・動作確認。
- eval/log.jsonl の `ts` は追記コマンド内で `date -Iseconds` を実行して使う(この 1 行追記に限りリダイレクト可)。
- サイクル数 = `verify.sh M3` の実行回数。M3 の上限 8 サイクルは未消化。cycle 7 が最初の verify 実行。

### M3 タスク(順に 1 つずつ commit)
- [ ] 状態ファイル更新 + commit(介入反映)
- [ ] 公式 Log4j2 Javadoc を fetch_url で 1 ページだけ読む(Appender API 確認、唯一の調査)
- [ ] pom.xml に log4j-core (provided) を追加
- [ ] ① ConsoleLog 最小版: Appender をルートロガーに付与、行を受け取ったらサーバーディレクトリの `mwc-capture.log` に書き出し(無限再帰に注意: plugin.logger 経由の出力はしない)→ ビルド → サーバー起動でキャプチャファイルにログ行が入ることを確認 → commit
- [ ] ② リングバッファ: 既定 1000 行(config console.buffer-size)、ANSI 除去。キャプチャファイルも除去済み文字列で書く(除去の証拠)→ ビルド → 動作確認 → commit
- [ ] ③ SSE: GET {bp}/api/console (Cookie 認証)。ヘッダ → バッファ全量 → ライブ行。購読者ごとに有界キュー+デーモン writer、10 秒 keepalive コメント → ビルド → curl で data 行確認 → commit
- [ ] ④ コマンド送信: POST {bp}/api/command {command} → メインスレッドで Bukkit.dispatchCommand(console, cmd)、CommandException → 400、監査ログ → ビルド → 動作確認
- [ ] `timeout 1200 bash scripts/verify.sh M3` (cycle 7) → 結果をその場で eval/log.jsonl に追記 → PASS なら PROGRESS 更新 + commit → M4

## 過去完了
- [x] M0 合格 (cycle 2, commit 2e36239)
- [x] M1 合格 (cycle 5, 24/24, commit cfa562a)
- [x] M2 合格 (cycle 6, commit df77243)

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [ ] M3 コンソール (SSE + コマンド送信) ← 現在(やり直し中)
- [ ] M4 最小フロントエンド
- [ ] M5 ファイル管理① 一覧・閲覧・テキスト編集
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
