package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import org.bukkit.Bukkit

/**
 * POST {base}/api/restart — サーバーの再起動 (管理者権限相当の操作)。
 *
 * Body: {"confirm":true}
 *  - confirm != true → 400 (UI 側でも確認ダイアログを必須にする)
 *  - 200 を先に返してからメインスレッドで停止 (サーバーが死ぬ前にレスポンスを完了させる)
 *  - 方式は設定 restart.method で選択:
 *    - "shutdown" (既定): Bukkit.shutdown()。再起動は外部監視
 *      (systemd の Restart=、Docker restart policy、検証スクリプトのループ等)に任せる
 *    - "restart": SpigotServer.restart() (spigot.yml の restart-script が必要。
 *      未取得の場合はログを出して shutdown にフォールバック)
 *  - 実行内容 (方式) を監査ログに残す
 */
class RestartHandler(private val plugin: MwcPlugin) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val body = readBody(ex, 4096)
        val confirm = try {
            val obj = gson.fromJson(body, JsonObject::class.java)
            obj?.get("confirm")?.takeIf { !it.isJsonNull }?.asBoolean
        } catch (_: Exception) {
            null
        }
        if (confirm != true) {
            send(ex, 400, "application/json", """{"error":"confirm=true required"}""")
            return
        }
        val method = plugin.config.restartMethod
        plugin.auth.audit(ex, "restart", "method=$method")
        // レスポンスを先にフラッシュする。停止は次のメインスレッド tick で行う。
        send(ex, 200, "application/json", """{"ok":true,"method":"$method"}""")
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (method == "restart") {
                doRestart()
            } else {
                Bukkit.shutdown()
            }
        })
    }

    /**
     * restart-script 方式。SpigotServer.restart() は paper-api のコンパイルパスに
     * 含まれないため反射で呼ぶ。使えない (未設定・別実装) 場合は shutdown にフォールバック。
     */
    private fun doRestart() {
        try {
            val server = Bukkit.getServer()
            val m = server.javaClass.getMethod("restart")
            m.invoke(server)
        } catch (t: Throwable) {
            plugin.logger.warning("MWC: restart method unavailable (${t.javaClass.simpleName}: ${t.message}); falling back to shutdown")
            Bukkit.shutdown()
        }
    }
}
