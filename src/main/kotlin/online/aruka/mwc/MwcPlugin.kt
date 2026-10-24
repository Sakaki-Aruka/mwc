package online.aruka.mwc

import org.bukkit.plugin.java.JavaPlugin

/**
 * MWC (Minecraft Web Console) - minimal web dashboard for Paper 1.21.4.
 */
class MwcPlugin : JavaPlugin() {

    override fun onEnable() {
        logger.info("MWC ${description.version} enabled")
    }

    override fun onDisable() {
        logger.info("MWC disabled")
    }
}
