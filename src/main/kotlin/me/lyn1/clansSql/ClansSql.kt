package me.lyn1.clansSql

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class ClansSql : JavaPlugin() {

    private lateinit var databaseManager: DatabaseManager

    override fun onEnable() {
        // Plugin startup logic
        dataFolder.mkdirs() // Ensure the data folder exists
        databaseManager = DatabaseManager(this)
        databaseManager.connect()
        getCommand("clan")?.setExecutor(ClanCommand(this))
        server.pluginManager.registerEvents(PlayerJoinListener(this), this)

        // Update usernames for all online players on plugin enable
        server.onlinePlayers.forEach { player: Player ->
            databaseManager.updatePlayerUsername(player.uniqueId.toString(), player.name)
        }
    }

    override fun onDisable() {
        // Plugin shutdown logic
        databaseManager.disconnect()
    }

    fun getDatabaseManager(): DatabaseManager {
        return databaseManager
    }
}
