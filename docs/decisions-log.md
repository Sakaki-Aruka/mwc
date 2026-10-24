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
