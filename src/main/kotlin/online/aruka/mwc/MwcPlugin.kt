package online.aruka.mwc

import org.bukkit.plugin.java.JavaPlugin

/**
 * MWC (Minecraft Web Console) - minimal web dashboard for Paper 1.21.4.
 */
class MwcPlugin : JavaPlugin() {

    lateinit var config: MwcConfig
        private set

    lateinit var auth: Auth
        private set

    lateinit var http: MwcHttp
        private set

    lateinit var consoleLog: ConsoleLog
        private set

    val startMs: Long = System.currentTimeMillis()

    override fun onEnable() {
        config = MwcConfig(this)
        config.load()
        consoleLog = ConsoleLog(this)
        consoleLog.attach()
        auth = Auth(this)
        auth.init()
        val h = MwcHttp(this, config, auth)
        http = h
        registerHandlers(h)
        try {
            h.start()
        } catch (e: Exception) {
            logger.severe("MWC: HTTP server failed to start on ${config.host}:${config.port}: ${e.message}")
            isEnabled = false
            return
        }
        logger.info("MWC ${description.version} enabled at http://${config.host}:${config.port}${config.basePath}/")
    }

    override fun onDisable() {
        try {
            http.stop()
        } catch (_: Exception) {
        }
        try {
            consoleLog.detach()
        } catch (_: Exception) {
        }
        logger.info("MWC disabled")
    }

    private fun registerHandlers(h: MwcHttp) {
        val status = StatusHandler(this, startMs)
        h.handlers["GET status"] = { ex, _ -> status.handle(ex) }
        val players = PlayersHandler(this)
        h.handlers["GET players"] = { ex, _ -> players.handle(ex) }
        val sse = ConsoleSseHandler(consoleLog)
        h.handlers["GET console"] = { ex, _ -> sse.handle(ex) }
    }
}
