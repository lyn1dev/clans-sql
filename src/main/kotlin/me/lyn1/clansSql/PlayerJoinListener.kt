package me.lyn1.clansSql

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(private val plugin: ClansSql) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val playerUUID = player.uniqueId.toString()
        val playerName = player.name
        val dbManager = plugin.getDatabaseManager()

        // Update player's username in the database
        dbManager.updatePlayerUsername(playerUUID, playerName)
    }
}
