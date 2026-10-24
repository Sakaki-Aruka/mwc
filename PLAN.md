# PLAN

## 現在のマイルストーン: 完了 (M0〜M7 すべて合格)

`PROGRESS.md` 末尾に `ALL MILESTONES PASSED` を記録済み。

### 最終結果
- 全 8 マイルストーンが機械的検証 (scripts/verify.sh + Paper 1.21.4 検証サーバー + curl) で合格。
- base-path `/` と `/admin/mc` の両方で全機能が動作 (M1 で 2 通り検証、M7 でサブパス素早い確認)。
- 最終サイクル: cycle 14 (M7 pass)。eval/log.jsonl に全サイクル記録 (15 行)。
- リモート push は人間の指示があるまでしない (ローカル commit のみ)。

## 過去完了
- [x] M0 合格 (cycle 2, commit 2e36239)
- [x] M1 合格 (cycle 5, 24/24, commit cfa562a)
- [x] M2 合格 (cycle 6, commit df77243)
- [x] M3 合格 (cycle 7, 初回 PASS, commits a3040d9 / 59075c0 / 3edca36 / 0306f0e)
- [x] M4 合格 (cycle 9, commit eb54424。cycle 8 は verify.sh 側バグで fail)
- [x] M5 合格 (cycle 11, commit 6db11e0。cycle 10 は dataFolder 相対パス NPE + verify.sh の E2BIG で fail。
  cycle 10.5 は追加漏れの後補)
- [x] M6 合格 (cycle 12, 初回 PASS, commit 743679f)
- [x] M7 合格 (cycle 14, commit 743b076。cycle 13 はコンパイルエラー + 旧 jar による fail)

## マイルストーン進捗 (詳細は PROGRESS.md)
- [x] M0 Maven + Kotlin 雛形
- [x] M1 HTTP + base-path + 認証 + 状態 API
- [x] M2 プレイヤー API
- [x] M3 コンソール (SSE + コマンド送信)
- [x] M4 最小フロントエンド
- [x] M5 ファイル管理① 一覧・閲覧・テキスト編集
- [x] M6 ファイル管理② アップロード・削除
- [x] M7 再起動

## 残課題 (人間の指示待ち)
- リモートリポジトリへの push (既定ではローカル commit のみ)
- 対象外機能 (バックアップ等) への着手 — 余力があっても指示なしにしない
- 外部公開 (0.0.0.0 バインド) に関する仕様変更
