package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import org.bukkit.Bukkit

/**
 * POST {base}/api/command — サーバーコンソールにコマンドを送信する。
 *
 * Body: {"command":"say hello"}
 *  - dispatchCommand は Bukkit API なのでメインスレッドで実行 (MainThread.call)
 *  - コマンドが存在しない等 (CommandException) → 400
 *  - 実行内容を監査ログに残す (管理者権限相当の操作)
 */
class CommandHandler(private val plugin: MwcPlugin) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val body = readBody(ex, MAX_LEN + 64L) ?: run {
            send(ex, 400, "application/json", """{"error":"body too large"}""")
            return
        }
        val cmd = try {
            val obj = gson.fromJson(body, JsonObject::class.java)
            obj?.get("command")?.takeIf { !it.isJsonNull }?.asString
        } catch (_: Exception) {
            null
        }
        if (cmd.isNullOrBlank()) {
            send(ex, 400, "application/json", """{"error":"missing command"}""")
            return
        }
        if (cmd.length > MAX_LEN) {
            send(ex, 400, "application/json", """{"error":"command too long"}""")
            return
        }
        plugin.auth.commandAudit(cmd)
        try {
            val ok = MainThread.call(plugin, 10_000) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            }
            if (ok) {
                send(ex, 200, "application/json", """{"ok":true}""")
            } else {
                send(ex, 400, "application/json", """{"error":"unknown or rejected command"}""")
            }
        } catch (e: Exception) {
            // メインスレッド実行で CommandException が RuntimeException にラップされて返ってくる
            val cause = e.cause ?: e
            send(ex, 400, "application/json",
                """{"error":"${escapeJson(cause.javaClass.simpleName)}: ${escapeJson(cause.message ?: "")}"}""")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").take(200)

    companion object {
        private const val MAX_LEN = 4096
    }
}
