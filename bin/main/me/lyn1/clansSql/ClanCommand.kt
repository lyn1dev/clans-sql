package me.lyn1.clansSql

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.sql.SQLException
import java.util.*

class ClanCommand(private val plugin: ClansSql) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can execute this command.")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("Usage: /clan [create <name> | invite <player> | accept | deny | list]")
            return true
        }

        val subCommand = args[0].lowercase()
        val dbManager = plugin.getDatabaseManager()
        val playerUUID = sender.uniqueId.toString()
        val playerRole = dbManager.getPlayerRole(playerUUID)

        // Commands that only owners can use
        if (subCommand == "promote" || subCommand == "demote" || subCommand == "transfer" || subCommand == "disband") {
            if (playerRole != "owner") {
                sender.sendMessage("§cYou must be the clan owner to use this command.")
                return true
            }
        }

        // Commands that owners and officers can use
        if (subCommand == "invite" || subCommand == "kick") {
            if (playerRole != "owner" && playerRole != "officer") {
                sender.sendMessage("§cYou must be an owner or officer to use this command.")
                return true
            }
        }

        // Members cannot use any commands except create, leave, accept, deny, and list
        if (playerRole == "member" && subCommand != "create" && subCommand != "leave" && subCommand != "help" && subCommand != "accept" && subCommand != "deny" && subCommand != "list") {
            sender.sendMessage("§cYou do not have permission to use this command.")
            return true
        }

        when (subCommand) {
            "create" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan create <name>")
                    return true
                }
                val clanName = args[1]
                createClan(sender, clanName)
            }
            "invite" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan invite <player>")
                    return true
                }
                val targetPlayerName = args[1]
                invitePlayer(sender, targetPlayerName)
            }
            "accept" -> {
                acceptInvitation(sender)
            }
            "deny" -> {
                denyInvitation(sender)
            }
            "promote" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan promote <player>")
                    return true
                }
                val targetPlayerName = args[1]
                changePlayerRole(sender, targetPlayerName, "officer")
            }
            "demote" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan demote <player>")
                    return true
                }
                val targetPlayerName = args[1]
                changePlayerRole(sender, targetPlayerName, "member")
            }
            "transfer" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan transfer <player>")
                    return true
                }
                val targetPlayerName = args[1]
                transferOwnership(sender, targetPlayerName)
            }
            "kick" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /clan kick <player>")
                    return true
                }
                val targetPlayerName = args[1]
                kickPlayer(sender, targetPlayerName)
            }
            "leave" -> {
                leaveClan(sender)
            }
            "disband" -> {
                disbandClan(sender)
            }
            "list" -> {
                listClanMembers(sender)
            }
            "help" -> {
                showHelpMessage(sender)
            }
            else -> {
                sender.sendMessage("§cUnknown subcommand. Use §e/clan help §cfor available commands.")
            }
        }
        return true
    }

    private fun showHelpMessage(sender: CommandSender) {
        val playerRole = if (sender is Player) {
            plugin.getDatabaseManager().getPlayerRole(sender.uniqueId.toString())
        } else null

        sender.sendMessage("§6========== §eClan Commands §6==========")
        sender.sendMessage("§e/clan create <name> §7- Create a new clan")
        sender.sendMessage("§e/clan leave §7- Leave your current clan")
        sender.sendMessage("§e/clan accept §7- Accept a clan invitation")
        sender.sendMessage("§e/clan deny §7- Deny a clan invitation")
        sender.sendMessage("§e/clan list §7- List all clan members")
        sender.sendMessage("§e/clan help §7- Show this help message")

        if (playerRole == "owner") {
            sender.sendMessage("§6--- Owner Commands ---")
            sender.sendMessage("§e/clan disband §7- Disband your clan")
            sender.sendMessage("§e/clan invite <player> §7- Invite a player to your clan")
            sender.sendMessage("§e/clan kick <player> §7- Kick a player from your clan")
            sender.sendMessage("§e/clan promote <player> §7- Promote a member to officer")
            sender.sendMessage("§e/clan demote <player> §7- Demote an officer to member")
            sender.sendMessage("§e/clan transfer <player> §7- Transfer ownership to another member")
        } else if (playerRole == "officer") {
            sender.sendMessage("§6--- Officer Commands ---")
            sender.sendMessage("§e/clan invite <player> §7- Invite a player to your clan")
            sender.sendMessage("§e/clan kick <player> §7- Kick a member from your clan")
        }

        sender.sendMessage("§6================================")
    }

    private fun listClanMembers(player: Player) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get player's clan
            var playerClanId: Int? = null
            var clanName: String? = null
            
            db.prepareStatement("SELECT p.clan_id, c.name FROM players p JOIN clans c ON p.clan_id = c.id WHERE p.uuid = ?").use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    playerClanId = rs.getInt("clan_id")
                    clanName = rs.getString("name")
                }
            }

            if (playerClanId == null) {
                player.sendMessage("§cYou are not in a clan.")
                return
            }

            // Get all clan members organized by role
            val owners = mutableListOf<String>()
            val officers = mutableListOf<String>()
            val members = mutableListOf<String>()

            db.prepareStatement("SELECT uuid, role FROM players WHERE clan_id = ? ORDER BY role DESC, uuid").use { ps ->
                ps.setInt(1, playerClanId!!)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val memberUUID = rs.getString("uuid")
                    val memberRole = rs.getString("role")
                    val memberName = plugin.getDatabaseManager().getPlayerUsername(memberUUID) ?: "Unknown" // Fetch username from DB
                    val memberPlayer = plugin.server.getOfflinePlayer(UUID.fromString(memberUUID)) // Still need this for online status
                    val onlineStatus = if (memberPlayer.isOnline) "§a●" else "§7●"
                    
                    when (memberRole) {
                        "owner" -> owners.add("$onlineStatus §e$memberName")
                        "officer" -> officers.add("$onlineStatus §b$memberName")
                        "member" -> members.add("$onlineStatus §7$memberName")
                    }
                }
            }

            // Display clan member list
            player.sendMessage("§6========== §eClan: $clanName §6==========")
            
            if (owners.isNotEmpty()) {
                player.sendMessage("§6Owner:")
                owners.forEach { player.sendMessage("  $it") }
            }
            
            if (officers.isNotEmpty()) {
                player.sendMessage("§bOfficers:")
                officers.forEach { player.sendMessage("  $it") }
            }
            
            if (members.isNotEmpty()) {
                player.sendMessage("§7Members:")
                members.forEach { player.sendMessage("  $it") }
            }
            
            val totalMembers = owners.size + officers.size + members.size
            player.sendMessage("§6Total Members: §e$totalMembers")
            player.sendMessage("§6================================")

        } catch (e: SQLException) {
            plugin.logger.severe("Error listing clan members: ${e.message}")
            player.sendMessage("§cAn error occurred while listing clan members. Please try again later.")
        }
    }

    private fun acceptInvitation(player: Player) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Check if player has a pending invitation
            var invitationId: Int? = null
            var clanId: Int? = null
            var clanName: String? = null
            var inviterName: String? = null

            db.prepareStatement("""
                SELECT i.id, i.clan_id, c.name, i.inviter_uuid 
                FROM clan_invitations i 
                JOIN clans c ON i.clan_id = c.id 
                WHERE i.invitee_uuid = ? AND i.status = 'pending'
            """).use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    invitationId = rs.getInt("id")
                    clanId = rs.getInt("clan_id")
                    clanName = rs.getString("name")
                    val inviterUUID = rs.getString("inviter_uuid")
                    val inviterPlayer = plugin.server.getOfflinePlayer(UUID.fromString(inviterUUID))
                    inviterName = inviterPlayer.name ?: "Unknown"
                }
            }

            if (invitationId == null) {
                player.sendMessage("§cYou have no pending clan invitations.")
                return
            }

            // Check if player is already in a clan
            db.prepareStatement("SELECT clan_id FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next() && rs.getObject("clan_id") != null) {
                    player.sendMessage("§cYou are already in a clan.")
                    return
                }
            }

            // Add player to the clan
            db.prepareStatement("INSERT INTO players (uuid, username, clan_id, role) VALUES (?, ?, ?, 'member')").use { ps ->
                ps.setString(1, player.uniqueId.toString())
                ps.setString(2, player.name) // Store username
                ps.setInt(3, clanId!!)
                ps.executeUpdate()
            }

            // Update invitation status
            db.prepareStatement("UPDATE clan_invitations SET status = 'accepted' WHERE id = ?").use { ps ->
                ps.setInt(1, invitationId!!)
                ps.executeUpdate()
            }

            player.sendMessage("§aYou have successfully joined clan '§e$clanName§a'!")

            // Notify clan members
            db.prepareStatement("SELECT uuid FROM players WHERE clan_id = ?").use { ps ->
                ps.setInt(1, clanId!!)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val memberUUID = rs.getString("uuid")
                    if (memberUUID != player.uniqueId.toString()) {
                        val memberPlayer = plugin.server.getPlayer(UUID.fromString(memberUUID))
                        memberPlayer?.sendMessage("§e${player.name} §ahas joined the clan!")
                    }
                }
            }

        } catch (e: SQLException) {
            plugin.logger.severe("Error accepting invitation: ${e.message}")
            player.sendMessage("§cAn error occurred while accepting the invitation. Please try again later.")
        }
    }

    private fun denyInvitation(player: Player) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Check if player has a pending invitation
            var invitationId: Int? = null
            var clanName: String? = null
            var inviterUUID: String? = null

            db.prepareStatement("""
                SELECT i.id, c.name, i.inviter_uuid 
                FROM clan_invitations i 
                JOIN clans c ON i.clan_id = c.id 
                WHERE i.invitee_uuid = ? AND i.status = 'pending'
            """).use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    invitationId = rs.getInt("id")
                    clanName = rs.getString("name")
                    inviterUUID = rs.getString("inviter_uuid")
                }
            }

            if (invitationId == null) {
                player.sendMessage("§cYou have no pending clan invitations.")
                return
            }

            // Update invitation status
            db.prepareStatement("UPDATE clan_invitations SET status = 'denied' WHERE id = ?").use { ps ->
                ps.setInt(1, invitationId!!)
                ps.executeUpdate()
            }

            player.sendMessage("§cYou have denied the invitation to join clan '§e$clanName§c'.")

            // Notify the inviter
            val inviterPlayer = plugin.server.getPlayer(UUID.fromString(inviterUUID!!))
            inviterPlayer?.sendMessage("§e${player.name} §chas denied your clan invitation.")

        } catch (e: SQLException) {
            plugin.logger.severe("Error denying invitation: ${e.message}")
            player.sendMessage("§cAn error occurred while denying the invitation. Please try again later.")
        }
    }

    private fun leaveClan(player: Player) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get player's clan and role
            var playerClanId: Int? = null
            var playerRole: String? = null
            var clanName: String? = null

            db.prepareStatement("SELECT p.clan_id, p.role, c.name FROM players p JOIN clans c ON p.clan_id = c.id WHERE p.uuid = ?").use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    playerClanId = rs.getInt("clan_id")
                    playerRole = rs.getString("role")
                    clanName = rs.getString("name")
                }
            }

            if (playerClanId == null) {
                player.sendMessage("§cYou are not in a clan.")
                return
            }

            if (playerRole == "owner") {
                player.sendMessage("§cYou cannot leave a clan as the owner. Use §e/clan disband §cor §e/clan transfer <player> §cfirst.")
                return
            }

            // Remove player from clan
            val deletePlayerSql = "DELETE FROM players WHERE uuid = ?"
            db.prepareStatement(deletePlayerSql).use { ps ->
                ps.setString(1, player.uniqueId.toString())
                ps.executeUpdate()
            }

            player.sendMessage("§aYou have successfully left clan '§e${clanName}§a'.")

            // Notify clan members
            val notifyMembersSql = "SELECT uuid FROM players WHERE clan_id = ?"
            db.prepareStatement(notifyMembersSql).use { ps ->
                ps.setInt(1, playerClanId!!)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val memberUUID = rs.getString("uuid")
                    val memberPlayer = plugin.server.getPlayer(UUID.fromString(memberUUID))
                    memberPlayer?.sendMessage("§e${player.name} §7has left the clan.")
                }
            }

        } catch (e: SQLException) {
            plugin.logger.severe("Error leaving clan: ${e.message}")
            player.sendMessage("§cAn error occurred while leaving the clan. Please try again later.")
        }
    }

    private fun disbandClan(player: Player) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get player's clan and role
            var playerClanId: Int? = null
            var playerRole: String? = null
            var clanName: String? = null

            db.prepareStatement("SELECT p.clan_id, p.role, c.name FROM players p JOIN clans c ON p.clan_id = c.id WHERE p.uuid = ?").use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    playerClanId = rs.getInt("clan_id")
                    playerRole = rs.getString("role")
                    clanName = rs.getString("name")
                }
            }

            if (playerClanId == null) {
                player.sendMessage("§cYou are not in a clan.")
                return
            }

            if (playerRole != "owner") {
                player.sendMessage("§cYou must be the clan owner to disband the clan.")
                return
            }

            // Get all clan members for notification
            val memberUUIDs = mutableListOf<String>()
            db.prepareStatement("SELECT uuid FROM players WHERE clan_id = ?").use { ps ->
                ps.setInt(1, playerClanId!!)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    memberUUIDs.add(rs.getString("uuid"))
                }
            }

            // Remove all players from the clan
            val deletePlayersSql = "DELETE FROM players WHERE clan_id = ?"
            db.prepareStatement(deletePlayersSql).use { ps ->
                ps.setInt(1, playerClanId!!)
                ps.executeUpdate()
            }

            // Delete pending invitations for this clan
            db.prepareStatement("DELETE FROM clan_invitations WHERE clan_id = ?").use { ps ->
                ps.setInt(1, playerClanId!!)
                ps.executeUpdate()
            }

            // Delete the clan
            val deleteClanSql = "DELETE FROM clans WHERE id = ?"
            db.prepareStatement(deleteClanSql).use { ps ->
                ps.setInt(1, playerClanId!!)
                ps.executeUpdate()
            }

            player.sendMessage("§aYou have successfully disbanded clan '§e${clanName}§a'.")

            // Notify all former members
            memberUUIDs.forEach { memberUUID ->
                if (memberUUID != player.uniqueId.toString()) {
                    val memberPlayer = plugin.server.getPlayer(UUID.fromString(memberUUID))
                    memberPlayer?.sendMessage("§cYour clan '§e${clanName}§c' has been disbanded by the owner.")
                }
            }

        } catch (e: SQLException) {
            plugin.logger.severe("Error disbanding clan: ${e.message}")
            player.sendMessage("§cAn error occurred while disbanding the clan. Please try again later.")
        }
    }

    private fun createClan(player: Player, clanName: String) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            player.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Check if player is already in a clan
            val checkPlayerSql = "SELECT clan_id FROM players WHERE uuid = ?"
            db.prepareStatement(checkPlayerSql).use { ps ->
                ps.setString(1, player.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next() && rs.getObject("clan_id") != null) {
                    player.sendMessage("§cYou are already in a clan.")
                    return
                }
            }

            // Check if clan name already exists
            val checkClanSql = "SELECT id FROM clans WHERE name = ?"
            db.prepareStatement(checkClanSql).use { ps ->
                ps.setString(1, clanName)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    player.sendMessage("§cA clan with that name already exists.")
                    return
                }
            }

            // Create clan
            val insertClanSql = "INSERT INTO clans (name, leader_uuid) VALUES (?, ?)"
            db.prepareStatement(insertClanSql).use { ps ->
                ps.setString(1, clanName)
                ps.setString(2, player.uniqueId.toString())
                ps.executeUpdate()
            }

            // Get the ID of the newly created clan
            val getClanIdSql = "SELECT id FROM clans WHERE name = ?"
            var clanId: Int? = null
            db.prepareStatement(getClanIdSql).use { ps ->
                ps.setString(1, clanName)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    clanId = rs.getInt("id")
                }
            }

            // Add player to players table as owner
            if (clanId != null) {
                val insertPlayerSql = "INSERT INTO players (uuid, username, clan_id, role) VALUES (?, ?, ?, ?)"
                db.prepareStatement(insertPlayerSql).use { ps ->
                    ps.setString(1, player.uniqueId.toString())
                    ps.setString(2, player.name) // Store username
                    ps.setInt(3, clanId!!)
                    ps.setString(4, "owner")
                    ps.executeUpdate()
                }
                player.sendMessage("§aClan '§e$clanName§a' created successfully! You are now the owner.")
            } else {
                player.sendMessage("§cFailed to retrieve clan ID. Please contact an administrator.")
            }

        } catch (e: SQLException) {
            plugin.logger.severe("Error creating clan: ${e.message}")
            player.sendMessage("§cAn error occurred while creating the clan. Please try again later.")
        }
    }

    private fun invitePlayer(sender: Player, targetPlayerName: String) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            sender.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get sender's clan and role
            var senderClanId: Int? = null
            var senderRole: String? = null
            var clanName: String? = null
            db.prepareStatement("SELECT p.clan_id, p.role, c.name FROM players p JOIN clans c ON p.clan_id = c.id WHERE p.uuid = ?").use { ps ->
                ps.setString(1, sender.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    senderClanId = rs.getInt("clan_id")
                    senderRole = rs.getString("role")
                    clanName = rs.getString("name")
                }
            }

            if (senderClanId == null) {
                sender.sendMessage("§cYou are not in a clan.")
                return
            }
            if (senderRole != "owner" && senderRole != "officer") {
                sender.sendMessage("§cYou must be an owner or officer to invite players.")
                return
            }

            val targetPlayer = plugin.server.getPlayer(targetPlayerName)
            if (targetPlayer == null) {
                sender.sendMessage("§cPlayer '$targetPlayerName' not found or is not online.")
                return
            }

            // Check if target player is already in a clan
            db.prepareStatement("SELECT clan_id FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next() && rs.getObject("clan_id") != null) {
                    sender.sendMessage("§cPlayer '${targetPlayer.name}' is already in a clan.")
                    return
                }
            }

            // Check if there's already a pending invitation
            db.prepareStatement("SELECT id FROM clan_invitations WHERE invitee_uuid = ? AND clan_id = ? AND status = 'pending'").use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                ps.setInt(2, senderClanId!!)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    sender.sendMessage("§cPlayer '${targetPlayer.name}' already has a pending invitation from your clan.")
                    return
                }
            }

            // Create invitation
            val insertInvitationSql = "INSERT INTO clan_invitations (clan_id, inviter_uuid, invitee_uuid, status) VALUES (?, ?, ?, 'pending')"
            db.prepareStatement(insertInvitationSql).use { ps ->
                ps.setInt(1, senderClanId!!)
                ps.setString(2, sender.uniqueId.toString())
                ps.setString(3, targetPlayer.uniqueId.toString())
                ps.executeUpdate()
            }

            sender.sendMessage("§aInvitation sent to '${targetPlayer.name}'.")
            targetPlayer.sendMessage("§6You have been invited to join clan '§e$clanName§6' by §e${sender.name}§6!")
            targetPlayer.sendMessage("§6Use §e/clan accept §6to join or §e/clan deny §6to decline.")

        } catch (e: SQLException) {
            plugin.logger.severe("Error inviting player: ${e.message}")
            sender.sendMessage("§cAn error occurred while inviting the player. Please try again later.")
        }
    }

    private fun changePlayerRole(sender: Player, targetPlayerName: String, newRole: String) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            sender.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get sender's clan and role
            var senderClanId: Int? = null
            var senderRole: String? = null
            db.prepareStatement("SELECT clan_id, role FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, sender.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    senderClanId = rs.getInt("clan_id")
                    senderRole = rs.getString("role")
                }
            }

            if (senderClanId == null) {
                sender.sendMessage("§cYou are not in a clan.")
                return
            }
            if (senderRole != "owner") {
                sender.sendMessage("§cYou must be the clan owner to change roles.")
                return
            }

            val targetPlayer = plugin.server.getOfflinePlayer(targetPlayerName)

            // Get target player's clan and role
            var targetClanId: Int? = null
            var targetRole: String? = null
            db.prepareStatement("SELECT clan_id, role FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    targetClanId = rs.getInt("clan_id")
                    targetRole = rs.getString("role")
                }
            }

            if (targetClanId == null || targetClanId != senderClanId) {
                sender.sendMessage("§cPlayer '${targetPlayer.name}' is not in your clan.")
                return
            }

            if (targetPlayer.uniqueId == sender.uniqueId) {
                sender.sendMessage("§cYou cannot change your own role.")
                return
            }

            if (targetRole == "owner") {
                sender.sendMessage("§cYou cannot change the role of the clan owner.")
                return
            }

            // Update player role
            val updateRoleSql = "UPDATE players SET role = ? WHERE uuid = ?"
            db.prepareStatement(updateRoleSql).use { ps ->
                ps.setString(1, newRole)
                ps.setString(2, targetPlayer.uniqueId.toString())
                ps.executeUpdate()
            }
            sender.sendMessage("§aPlayer '${targetPlayer.name}' has been ${if (newRole == "officer") "promoted" else "demoted"}.")
            
            if (targetPlayer.isOnline) {
                targetPlayer.player?.sendMessage("§aYou have been ${if (newRole == "officer") "promoted to officer" else "demoted to member"}!")
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Error changing player role: ${e.message}")
            sender.sendMessage("§cAn error occurred while changing the player's role. Please try again later.")
        }
    }

    private fun transferOwnership(sender: Player, targetPlayerName: String) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            sender.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get sender's clan and role
            var senderClanId: Int? = null
            var senderRole: String? = null
            db.prepareStatement("SELECT clan_id, role FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, sender.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    senderClanId = rs.getInt("clan_id")
                    senderRole = rs.getString("role")
                }
            }

            if (senderClanId == null) {
                sender.sendMessage("§cYou are not in a clan.")
                return
            }
            if (senderRole != "owner") {
                sender.sendMessage("§cYou must be the clan owner to transfer ownership.")
                return
            }

            val targetPlayer = plugin.server.getOfflinePlayer(targetPlayerName)

            // Get target player's clan and role
            var targetClanId: Int? = null
            db.prepareStatement("SELECT clan_id FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    targetClanId = rs.getInt("clan_id")
                }
            }

            if (targetClanId == null || targetClanId != senderClanId) {
                sender.sendMessage("§cPlayer '${targetPlayer.name}' is not in your clan.")
                return
            }

            if (targetPlayer.uniqueId == sender.uniqueId) {
                sender.sendMessage("§cYou cannot transfer ownership to yourself.")
                return
            }

            // Update clan leader
            val updateClanLeaderSql = "UPDATE clans SET leader_uuid = ? WHERE id = ?"
            db.prepareStatement(updateClanLeaderSql).use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                ps.setInt(2, senderClanId!!)
                ps.executeUpdate()
            }

            // Update sender's role to member
            val updateSenderRoleSql = "UPDATE players SET role = 'member' WHERE uuid = ?"
            db.prepareStatement(updateSenderRoleSql).use { ps ->
                ps.setString(1, sender.uniqueId.toString())
                ps.executeUpdate()
            }

            // Update target player's role to owner
            val updateTargetRoleSql = "UPDATE players SET role = 'owner' WHERE uuid = ?"
            db.prepareStatement(updateTargetRoleSql).use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                ps.executeUpdate()
            }

            sender.sendMessage("§aYou have successfully transferred clan ownership to '${targetPlayer.name}'. Your role is now 'member'.")
            if (targetPlayer.isOnline) {
                targetPlayer.player?.sendMessage("§aYou are now the owner of your clan!")
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Error transferring ownership: ${e.message}")
            sender.sendMessage("§cAn error occurred while transferring ownership. Please try again later.")
        }
    }

    private fun kickPlayer(sender: Player, targetPlayerName: String) {
        val db = plugin.getDatabaseManager().getConnection() ?: run {
            sender.sendMessage("§cDatabase connection error. Please try again later.")
            return
        }

        try {
            // Get sender's clan and role
            var senderClanId: Int? = null
            var senderRole: String? = null
            db.prepareStatement("SELECT clan_id, role FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, sender.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    senderClanId = rs.getInt("clan_id")
                    senderRole = rs.getString("role")
                }
            }

            if (senderClanId == null) {
                sender.sendMessage("§cYou are not in a clan.")
                return
            }
            if (senderRole != "owner" && senderRole != "officer") {
                sender.sendMessage("§cYou must be an owner or officer to kick players.")
                return
            }

            val targetPlayer = plugin.server.getOfflinePlayer(targetPlayerName)

            // Get target player's clan and role
            var targetClanId: Int? = null
            var targetRole: String? = null
            db.prepareStatement("SELECT clan_id, role FROM players WHERE uuid = ?").use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    targetClanId = rs.getInt("clan_id")
                    targetRole = rs.getString("role")
                }
            }

            if (targetClanId == null || targetClanId != senderClanId) {
                sender.sendMessage("§cPlayer '${targetPlayer.name}' is not in your clan.")
                return
            }

            if (targetPlayer.uniqueId == sender.uniqueId) {
                sender.sendMessage("§cYou cannot kick yourself.")
                return
            }

            if (targetRole == "owner") {
                sender.sendMessage("§cYou cannot kick the clan owner.")
                return
            }

            if (senderRole == "officer" && targetRole == "officer") {
                sender.sendMessage("§cOfficers cannot kick other officers.")
                return
            }

            // Remove player from players table
            val deletePlayerSql = "DELETE FROM players WHERE uuid = ?"
            db.prepareStatement(deletePlayerSql).use { ps ->
                ps.setString(1, targetPlayer.uniqueId.toString())
                ps.executeUpdate()
            }
            sender.sendMessage("§aPlayer '${targetPlayer.name}' has been kicked from the clan.")
            if (targetPlayer.isOnline) {
                targetPlayer.player?.sendMessage("§cYou have been kicked from your clan!")
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Error kicking player: ${e.message}")
            sender.sendMessage("§cAn error occurred while kicking the player. Please try again later.")
        }
    }
}
