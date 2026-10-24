# PROGRESS

## フェーズ0 (人間側)
- 2026-10-24: `scripts/check-env.sh --agent` 全項目 OK (java 21.0.12.1, maven 3.8.7, paper-api resolved, test-server 起動/停止 OK, git 設定済み)。ハーネス側項目はサンドボックス外で確認済み。

## M0 未満の作業
- 2026-10-24: リポジトリ作成 (git init, .gitignore: test-server/・tmp/・target/・.env, README.md)。
  一次情報で確認: api-version は 1.13–26.3 の有効値 (1.20.5〜 はマイナー版対応、docs.papermc.io/paper/dev/plugin-yml/)。
  Javadoc 1.21.4 で Server.getTPS()/getAverageTickTime()/getTickTimes()/restart()、Player.getPing() の存在を確認。
  spigot.yml の restart-script 仕様を確認 (docs.papermc.io/paper/reference/spigot-configuration/)。
