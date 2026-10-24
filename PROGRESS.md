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

