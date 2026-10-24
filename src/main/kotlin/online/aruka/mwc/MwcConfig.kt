package online.aruka.mwc

import org.bukkit.configuration.file.FileConfiguration

/** MWC 設定 (plugins/MWC/config.yml)。デフォルト値はここに集約。 */
class MwcConfig(private val plugin: MwcPlugin) {

    var host: String = "127.0.0.1"
    var port: Int = 8420

    /** base-path。正規化済み(先頭 /、末尾 / なし。"/" は "") */
    var basePath: String = ""

    var fileRoots: List<String> = listOf("server.properties", "bukkit.yml", "spigot.yml", "config", "plugins")
    var maxEditSize: Long = 1L * 1024 * 1024          // 1 MB (テキスト編集)
    var maxUploadSize: Long = 100L * 1024 * 1024      // 100 MB
    var recursiveDelete: Boolean = false
    var consoleBufferSize: Int = 1000
    var restartMethod: String = "shutdown"            // shutdown | restart
    var textExtensions: Set<String> = setOf(
        ".yml", ".yaml", ".json", ".properties", ".txt", ".conf",
        ".toml", ".cfg", ".xml", ".md", ".ini", ".lst", ".dat"
    )

    fun load() {
        plugin.saveDefaultConfig()
        val c = plugin.getConfig()
        host = c.getString("http.host", host) ?: host
        port = c.getInt("http.port", port)
        basePath = normalizeBasePath(c.getString("http.base-path", "") ?: "")
        fileRoots = c.getStringList("file-manager.roots").ifEmpty { fileRoots }
        maxEditSize = c.getLong("file-manager.max-edit-size", maxEditSize)
        maxUploadSize = c.getLong("file-manager.max-upload-size", maxUploadSize)
        recursiveDelete = c.getBoolean("file-manager.recursive-delete", recursiveDelete)
        consoleBufferSize = c.getInt("console.buffer-size", consoleBufferSize)
        restartMethod = c.getString("restart.method", restartMethod) ?: "shutdown"
        val exts = c.getStringList("file-manager.text-extensions")
        if (exts.isNotEmpty()) textExtensions = exts.map { it.lowercase() }.toSet()
    }

    fun reload() = load()

    companion object {
        fun normalizeBasePath(raw: String): String {
            var p = raw.trim()
            if (p.isEmpty() || p == "/") return ""
            if (!p.startsWith("/")) p = "/$p"
            while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
            return p
        }
    }
}
