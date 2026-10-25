# MWC (Minecraft Web Console)

Paper 1.21.4 向けの最小 Web ダッシュボードプラグイン。
MCDash の必須機能のみの自家再実装(参考実装のコード・名称・ロゴは流用しない)。

## 機能
- コンソール表示(ログの SSE リアルタイム配信 + コマンド送信)
- サーバー状態表示(TPS・メモリ・稼働時間・バージョン・人数)
- オンラインプレイヤー表示
- サーバー再起動(`shutdown` 方式 / Spigot `restart-script` 方式)
- ファイル管理(一覧・テキスト編集・アップロード・削除。許可ルート + 保護対象 + パストラバーサル検証)
- 任意のサブパス(`base-path`)配下で動作(既定 `/`)
- 初回起動時にランダムトークンを生成しコンソールに一度だけ表示。HttpOnly Cookie セッション + CSRF ヘッダ + ログインレート制限

## ビルド
Java 21 と Maven が必要。
```
mvn package        # -> target/MWC.jar(Kotlin の標準ライブラリを同梱)
```

## 導入
1. `target/MWC.jar` をサーバーの `plugins/` に置いて起動する(jar のファイル名は変えてもよい)。
2. 初回起動時、コンソールに `MWC token: <64 桁の16進>` が一度だけ表示される。控えておく。
   トークンは `plugins/MWC/token.key` に保存される(Web からは読めない)。
   作り直すときはサーバーを止めて `token.key` を消し、起動し直す。
3. ブラウザで `http://127.0.0.1:8420/` を開き、トークンでログインする。
   セッションはメモリに持つため、サーバーを再起動したらログインし直す。

## 設定(`plugins/MWC/config.yml`)
| キー | 既定 | 内容 |
|---|---|---|
| `http.host` | `127.0.0.1` | 待ち受けアドレス。外部に出すときはリバースプロキシを前に置く |
| `http.port` | `8420` | 待ち受けポート |
| `http.base-path` | `/` | 待ち受けパス(例 `/admin/mc`)。API・画面・SSE すべてがこの配下に載る |
| `file-manager.roots` | `server.properties`, `bukkit.yml`, `spigot.yml`, `config`, `plugins` | 操作できる範囲(サーバーディレクトリからの相対)。ワールドや `logs/` は既定では対象外 |
| `file-manager.max-edit-size` | `1048576` | テキストとして閲覧・編集できる上限(バイト) |
| `file-manager.max-upload-size` | `104857600` | アップロードの上限(バイト) |
| `file-manager.recursive-delete` | `false` | 中身のあるディレクトリの削除を許すか(シンボリックリンクはたどらない) |
| `file-manager.text-extensions` | `.yml` `.json` `.properties` `.txt` など | テキストとして扱う拡張子 |
| `console.buffer-size` | `1000` | 接続時に送るログの行数 |
| `restart.method` | `shutdown` | `shutdown`: 停止して外部の監視に起動し直させる / `restart`: Spigot の `restart-script` を使う |

設定の変更は再起動で反映される。

## 再起動の方式
- `shutdown`(既定): サーバーを停止するだけ。systemd の `Restart=always` や Docker の restart policy など、
  外部の監視が起動し直す前提。監視がなければ止まったままになる。systemd の例:
  ```
  [Service]
  WorkingDirectory=/srv/minecraft
  ExecStart=/usr/bin/java -Xmx4G -jar paper-1.21.4-232.jar --nogui
  Restart=always
  RestartSec=5
  ```
- `restart`: `spigot.yml` の `settings.restart-script` に起動スクリプトを設定しておく。
  スクリプトが無い場合、サーバーは停止するだけになる。

## リバースプロキシ配下で使う
`http.base-path` をプロキシのパスと揃える。SSE が途中で止まらないよう、バッファリングを切る。nginx の例:
```
location /mc/ {
    proxy_pass http://127.0.0.1:8420/mc/;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_read_timeout 1h;
}
```
(`base-path: /mc` のとき)。外部に公開するなら HTTPS にする。

## セキュリティ
- 既定は `127.0.0.1` にだけバインドする。
- `plugins/` への jar のアップロードはサーバー上でコードを実行できることと同じ。トークンは管理者のパスワードとして扱う。
- 状態を変える API(ログイン、コマンド、編集、アップロード、削除、再起動)は `X-MWC-Request: mwc` ヘッダが必須(CSRF 対策)。
- ログインの失敗が 60 秒に 5 回続くと、その IP からのログインを 60 秒止める。
  リバースプロキシ配下では全員が同じ IP に見えるため、止まるのは全員になる。
- コマンド・編集・アップロード・削除・再起動は、セッションの識別子(Cookie の SHA-256 の先頭 8 桁)・IP・パス・サイズをサーバーログに残す(`MWC audit:`)。
- 保護対象(閲覧・編集・上書き・削除できない): `plugins/MWC/token.key`、MWC 自身の jar、`console.capture-file` のファイル。

## API(すべて `{base-path}/api/` 配下。ログイン以外はセッション Cookie が必要)
| メソッドとパス | 内容 |
|---|---|
| `POST login` `{"token"}` | セッション Cookie を発行 |
| `GET status` | TPS・平均 tick・メモリ・稼働時間・バージョン・人数 |
| `GET players` | `[{name, uuid, ping, world}]` |
| `GET console` | ログの SSE(接続時にバッファを送る) |
| `POST command` `{"command"}` | コンソールコマンドを実行 |
| `GET files?path=` | 一覧 `[{name, type, size, mtime}]`(`path` 空で許可ルート) |
| `GET file?path=` | テキストファイルの内容(`ETag` 付き) |
| `PUT file?path=` `{"content","etag"}` | 保存(ETag が違えば 409。保存前に `.bak` を作る) |
| `POST files/upload?path=[&overwrite=true]` | ボディをそのままファイルにする(既存は `overwrite=true` のときだけ上書きし、`.bak` を作る) |
| `DELETE files?path=&confirm=true` | 削除 |
| `POST restart` `{"confirm":true}` | 再起動 |

## 検証(開発用)
```
scripts/run-test-server.sh start|stop|loop     # test-server/ (Paper 1.21.4) を操作
scripts/verify.sh M0..M7                       # ビルド→配置→起動→HTTP確認→停止 の一気通貫判定
```
