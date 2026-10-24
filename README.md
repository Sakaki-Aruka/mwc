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
```
mvn package        # -> target/mwc.jar
```

## 検証
```
scripts/run-test-server.sh start|stop|loop     # test-server/ (Paper 1.21.4) を操作
scripts/verify.sh M0..M7                       # ビルド→配置→起動→HTTP確認→停止 の一気通貫判定
```

## セキュリティ既定
- `127.0.0.1` にバインド(外部公開は設定で明示)
- 認証必須(初回生成トークン)
- 状態変更 API は `X-MWC-Request` ヘッダ必須(CSRF 対策)
