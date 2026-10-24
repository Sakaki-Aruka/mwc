# PLAN

## 現在のマイルストーン: M0 (Maven + Kotlin の雛形)

### M0 タスク
- [x] 環境確認 (check-env.sh --agent 全項目 OK、2026-10-24)
- [x] git init / .gitignore / README.md
- [x] Paper docs で Java 21 / api-version '1.21.4' / Maven 座標を確認し DECISIONS.md へ記録
- [ ] pom.xml (Kotlin + kotlin-maven-plugin, paper-api 1.21.4-R0.1-SNAPSHOT compileOnly, maven-shade-plugin で kotlin-stdlib 同梱)
- [ ] plugin.yml (name: MWC, main: online.aruka.mwc.MwcPlugin, api-version: '1.21.4')
- [ ] MwcPlugin 雛形 (onEnable でログ出力)
- [ ] scripts/run-test-server.sh (start/stop/loop、fifo 経由で stop 送信)
- [ ] scripts/verify.sh (ビルド→配置→起動→HTTP確認→停止、M 引数でチェック項目切替)
- [ ] mvn package 成功
- [ ] test-server に配置して有効化されることを確認 (verify.sh M0)
- [ ] commit

### 次: M1 (HTTP サーバー + base-path + 認証 + 状態 API)
タスクは M0 完了時に分解してここに書き直す。

## マイルストーン進捗 (詳細は PROGRESS.md)
- [ ] M0 Maven + Kotlin 雛形 ← 現在
- [ ] M1 HTTP + base-path + 認証 + 状態 API
- [ ] M2 プレイヤー API
- [ ] M3 コンソール (SSE + コマンド送信)
- [ ] M4 最小フロントエンド
- [ ] M5 ファイル管理① 一覧・閲覧・編集
- [ ] M6 ファイル管理② アップロード・削除
- [ ] M7 再起動
