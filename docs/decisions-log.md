# 決定ログ (経緯・失敗の詳細)

## 2026-10-24
- `scripts/check-env.sh --agent` 実行: 全項目 OK。NG なし。
- Paper docs (plugin-yml) 確認: `api-version` の有効値は 1.13 - 26.3。1.20.5 以降はマイナー版がサポートされるため、1.21.4 には `api-version: '1.21.4'` を使う。
- paper-api 1.21.4-R0.1-SNAPSHOT の pom を確認: gson 2.11.0 / log4j-api 2.17.1 が compile スコープで宣言されており、サーバー実行時に必ず classpath にある (Gson・Log4j2 API は provided にしてよい)。
- 再起動方式の調査 (Javadoc `Server#restart()`: "If the server administrator has not configured restarting, the server will stop" + spigot.yml `restart-script` ドキュメント):
  `restart()` は spigot.yml の restart-script 設定時にスクリプト実行、未設定時は単純に停止。
  → 既定を `shutdown` にする (外部監視に委ねる方式が動作が予測可能で、M7 検証のループスクリプトとも整合)。`restart` も設定で選べる。

## 2026-10-24 (21:30 監督役介入)
- M3 着手後、コードを書かずに 45 コマンド以上の調査(約 30 分以上)を続けていたため監督役が停止を指示。
  ツリーを M2 の commit(df77243)に戻し、AGENTS.md に追注を commit(6a521b9)。M3 は最初からやり直し。
  失敗した調査(繰り返さない): `~/.m2` の log4j jar を python zipfile で開いてクラス一覧を当たった
  (log4j-api-2.17.1.jar に Message/ErrorHandler クラスが見当たらず混乱)、GitHub API でソース一覧を確認、
  log4j Javadoc のバージョン付き URL を推測して 404(https://logging.apache.org/log4j/2.x/javadoc/2.17.1/... は存在しない)。
- 新しい方針(監督役追注より):
  1. 公式 docs / Javadoc を fetch_url で 1〜2 ページだけ読み、最小コードを書く。
  2. ビルド → 検証サーバーで挙動確認。
  3. エラーが出たらメッセージ/スタックトレースから原因究明、分からない場合のみ web_search。
  4. 動いたら commit。リングバッファ → SSE → コマンド送信の順に 1 つずつ足しては動かす。
  - `~/.m2` やサーバー jar(Javadoc jar 含む)を unzip/jar/python zipfile で開かない。Javadoc は公式サイト fetch_url。
  - 1 疑問あたり web_search+fetch_url 合計 3 回まで。決まらなければ書いてビルドして確かめる。
  - eval/log.jsonl の ts は追記コマンド内で date -Iseconds を実行しその出力を使う(この 1 行の追記に限りリダイレクト可)。
- log4j の版本決定: コンパイルは log4j-core 2.17.1(provided)を使用。Paper 実行時が 2.24.1 と
  混在する場合は、API の互換性(2.17.1 でコンパイルしたものを 2.24.1 で実行)が成立する前提で進める。
  実際には Paper は自前の log4j を使用するため、provided なら実行時に Paper 側の log4j が使われる。
- (2026-10-24) log4j-core 2.17.1 の API 形をコンパイラ出力で確定(3 回のコンパイル失敗から、各エラーメッセージが
  正確なシグネチャを教えてくれた):
  - 抽象メソッドは `append(LogEvent)`。`appendInternal` は 2.17.1 にない(新バージョン用)。
  - コンストラクタ: `AbstractAppender(String name, Filter, Layout, boolean ignoreExceptions)` の 4 引数が存在。
    `ErrorHandlingMode` という enum は 2.17.1 に存在しない。
  - `LoggerConfig.addAppender(Appender, Level, Filter)`(3 引数必須、null も可) /
    `LoggerConfig.removeAppender(String name)`(名前で削除)。
  - `LogManager.getContext(false) as? org.apache.logging.log4j.core.LoggerContext`(インターフェース)が成立。
    `org.apache.logging.log4j.core.impl.Log4jContext` というクラスは 2.17.1 に存在しない。
    `getConfiguration()` の Kotlin プロパティ名は `.configuration`(`.config` ではない)。
  - 文字列取得: `event.message?.formattedMessage`。
- 検証: scripts/m3-step1-test.sh で PASS(起動→stdin マーカー送信→plugins/MWC/mwc-capture.log に
  マーカー行・起動ログ行が記録、サーバーログに例外なし、'console capture active' 行あり)。
  → 実行時の Paper 側 log4j でも 2.17.1 でコンパイルした Appender は動作することを確認。
- 副産物の確認: キャプチャファイルの先頭行はトークン表示行 → capture.log は M5/M6 の保護対象に追加。

## 2026-10-25

- M5 verify 再実行の失敗原因 (E2BIG): verify.sh の `http()` が `curl --data "$data"` で 1.1MB ボディを
  1 引数に渡していた。Linux の単一引数上限 (MAX_ARG_STRLEN = 128KB) を超え
  `curl: Argument list too long` → verify 側 `|| CODE="000"` にフォールバックし oversize 編集チェックが 000。
  サーバー側の問題ではなかった(要求が到達していなかった)。
  修正: ボディを一時ファイルに書き `--data-binary "@$file"` で渡す (commit 6db11e0)。
- eval/log.jsonl の結合バグ: printf 追記時にファイル末尾の改行が欠落していたため 2 行が 1 行に結合
  (`...true}{"ts": ...`)。python で `}{"ts":` を分割して修正し全行 json.loads で検証。
  再発防止: 追記前に `tail -c 1` で末尾が改行か確認する (M6 の cycle 12 追記でも再発し同様に修正)。
- eval の追記漏れ (cycle 10.5): M5 の NPE 修正後の再実行 (26/27、E2BIG 起因の 1 check fail) の
  その場追記を漏らしていた。cycle 8 の前例 (late_append) に従い補足行を追加。
  ts は推測せず、当該実行のサーバーログ最終タイムスタンプ (01:19:38 = 停止時、
  `test-server/logs/2026-10-25-2.log.gz`。次回の 01:23:57 にローテーション) から復元。
  既存行の書き換えを避けるため cycle 番号は "10.5" (既存 10 と 11 の間の実行を示す)。
