# DECISIONS

**現在有効な決定**だけを短く書く(目安 150 行以内)。経緯や失敗の詳細は `docs/decisions-log.md` に追記する。

## フェーズ0(2026-10-23、監督役 Claude が記録)
- Paper 1.21.4 の要求 Java は**最小 21**(出典: PaperMC Fill API v3 `projects/paper/versions/1.21.4`)。p100 には OpenJDK 21 と Maven 3.8.7 がある。
- 検証サーバー: `test-server/paper-1.21.4-232.jar`(最新の STABLE ビルド 232、sha256 照合済み)。EULA は人間が同意済み。
  1.21.4 系は upstream のサポートが終了している(2025-07-17)。
- `io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT` は `https://repo.papermc.io/repository/maven-public/` から取得できる。
- ハーネスは Codex CLI(AGENTS.md の注記を参照)。ハーネスの構成の記録は作業ディレクトリの外にあり、読む必要はない。
- 使用中のポート: 8080(LLM)、8081(ハーネス)、25565(検証サーバーの Minecraft)。

## 実装の決定(エージェントが追加、2026-10-24)
- `plugin.yml` の `api-version` は **`'1.21.4'`**(docs: 有効値 1.13–26.3、1.20.5〜 はマイナー版対応)。
- Paper 1.21.4 用 API を Javadoc(jd.papermc.io/paper/1.21.4)で確認済み:
  `Server#getTPS()`, `getAverageTickTime()`(ms), `getTickTimes()`(ns), `restart()`, `Player#getPing()`(int, ms)。
- paper-api 1.21.4 の pom: gson 2.11.0 と log4j-api 2.17.1 はサーバー実行時に必ず classpath にある
  → Gson・Log4j2 API は `provided`(実行時提供)としてよい。log4j-core もサーバーが提供。
- Kotlin stdlib は `plugin.yml` の `libraries:` に**載せない**(Paper は Maven Central のみで、
  maven.org が壊れた/遅い場合にサーバー起動が危うくなるため)。
  **`maven-shade-plugin` で同梱**する(AGENTS 3 章の「まず前者を試し、不可なら後者」で後者を選択)。
- HTTP サーバーは JDK 標準 `com.sun.net.httpserver`、ログ配信は SSE(依存増やさないため)。
- 再起動方式の既定は **`shutdown`**(`Bukkit.shutdown()` + 外部監視)。理由: `restart()` は
  spigot.yml の `restart-script` が設定されていなければ単に停止するだけ(Javadoc 記載)で、
  外部監視方式の方が動作が予測可能。`restart` も設定で選べる。
- MWC の HTTP 既定ポートは **8420**(8080/8081/25565 は使用中)。
