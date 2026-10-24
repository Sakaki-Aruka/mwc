package online.aruka.mwc

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import org.bukkit.Bukkit

/** GET {base}/api/players -> [{name, uuid, ping, world}] (0 人なら空配列) */
class PlayersHandler(private val plugin: MwcPlugin) {

    private val gson = Gson()

    fun handle(ex: HttpExchange) {
        val arr: JsonArray = MainThread.call(plugin, 10_000) {
            val a = JsonArray()
            for (p in Bukkit.getOnlinePlayers()) {
                val o = JsonObject()
                o.addProperty("name", p.name)
                o.addProperty("uuid", p.uniqueId.toString())
                o.addProperty("ping", p.ping) // int, ms
                o.addProperty("world", p.world.name)
                a.add(o)
            }
            a
        }
        send(ex, 200, "application/json; charset=utf-8", gson.toJson(arr))
    }
}
