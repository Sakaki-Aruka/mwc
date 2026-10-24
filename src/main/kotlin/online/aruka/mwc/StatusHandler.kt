package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import org.bukkit.Bukkit

/** GET {base}/api/status -> TPS / メモリ / 稼働時間 / バージョン / 人数 */
class StatusHandler(private val plugin: MwcPlugin, private val startMs: Long) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val obj = MainThread.call(plugin, 10_000) {
            val server = Bukkit.getServer()
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024
            val o = JsonObject()
            o.add("tps", gson.toJsonTree(server.getTPS().toList()))
            o.addProperty("avg_tick_time_ms", server.averageTickTime)
            o.addProperty("memory_used_mb", (rt.totalMemory() - rt.freeMemory()) / mb)
            o.addProperty("memory_max_mb", rt.maxMemory() / mb)
            o.addProperty("uptime_seconds", (System.currentTimeMillis() - startMs) / 1000)
            o.addProperty("version", server.minecraftVersion)
            o.addProperty("bukkit_version", server.bukkitVersion)
            o.addProperty("players_online", server.onlinePlayers.size)
            o.addProperty("players_max", server.maxPlayers)
            o
        }
        send(ex, 200, "application/json; charset=utf-8", gson.toJson(obj))
    }
}
