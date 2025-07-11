package me.lyn1.clansSql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class DatabaseManager(private val plugin: ClansSql) {

    private var connection: Connection? = null

    fun connect() {
        try {
            val url = "jdbc:sqlite:${plugin.dataFolder.absolutePath}/clans.db"
            connection = DriverManager.getConnection(url)
            plugin.logger.info("SQLite database connected.")
            createTables()
        } catch (e: SQLException) {
            plugin.logger.severe("Could not connect to SQLite database: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            connection?.close()
            plugin.logger.info("SQLite database disconnected.")
        } catch (e: SQLException) {
            plugin.logger.severe("Could not disconnect from SQLite database: ${e.message}")
        }
    }

    private fun createTables() {
        val createClansTable = """
            CREATE TABLE IF NOT EXISTS clans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                leader_uuid TEXT NOT NULL,
                home_location TEXT
            );
        """.trimIndent()

        val createPlayersTable = """
            CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                clan_id INTEGER,
                role TEXT NOT NULL DEFAULT 'member',
                FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE SET NULL
            );
        """.trimIndent()

        val createInvitationsTable = """
            CREATE TABLE IF NOT EXISTS clan_invitations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                clan_id INTEGER NOT NULL,
                invitee_uuid TEXT NOT NULL,
                inviter_uuid TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE,
                UNIQUE(clan_id, invitee_uuid)
            );
        """.trimIndent()

        val addUsernameColumn = """
            ALTER TABLE players ADD COLUMN username TEXT;
        """.trimIndent()

        try {
            connection?.createStatement()?.use { statement ->
                statement.execute(createClansTable)
                statement.execute(createPlayersTable)
                statement.execute(createInvitationsTable)
                
                // Add username column if it doesn't exist
                try {
                    statement.execute(addUsernameColumn)
                    plugin.logger.info("Added 'username' column to 'players' table.")
                } catch (e: SQLException) {
                    if (e.message?.contains("duplicate column name: username") == true) {
                        plugin.logger.info("'username' column already exists in 'players' table.")
                    } else {
                        plugin.logger.severe("Could not add 'username' column to 'players' table: ${e.message}")
                    }
                }
                plugin.logger.info("Database tables created or already exist.")
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not create database tables: ${e.message}")
        }
    }

    fun getConnection(): Connection? {
        return connection
    }

    fun getPlayerClanId(playerUUID: String): Int? {
        val getClanIdSql = "SELECT clan_id FROM players WHERE uuid = ?"
        try {
            connection?.prepareStatement(getClanIdSql)?.use { ps ->
                ps.setString(1, playerUUID)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val clanId = rs.getObject("clan_id")
                    if (clanId != null) {
                        return rs.getInt("clan_id")
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get clan ID for player $playerUUID: ${e.message}")
        }
        return null
    }

    fun getPlayerRole(playerUUID: String): String? {
        val getRoleSql = "SELECT role FROM players WHERE uuid = ?"
        try {
            connection?.prepareStatement(getRoleSql)?.use { ps ->
                ps.setString(1, playerUUID)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return rs.getString("role")
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get role for player $playerUUID: ${e.message}")
        }
        return null
    }

    fun updatePlayerUsername(playerUUID: String, username: String) {
        val updateUsernameSql = "UPDATE players SET username = ? WHERE uuid = ?"
        try {
            connection?.prepareStatement(updateUsernameSql)?.use { ps ->
                ps.setString(1, username)
                ps.setString(2, playerUUID)
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not update username for player $playerUUID: ${e.message}")
        }
    }

    fun getPlayerUsername(playerUUID: String): String? {
        val getUsernameSql = "SELECT username FROM players WHERE uuid = ?"
        try {
            connection?.prepareStatement(getUsernameSql)?.use { ps ->
                ps.setString(1, playerUUID)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return rs.getString("username")
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get username for player $playerUUID: ${e.message}")
        }
        return null
    }

    fun getClanMembers(clanId: Int): List<Pair<String, String>> {
        val getMembersSql = "SELECT uuid, username, role FROM players WHERE clan_id = ?"
        val members = mutableListOf<Pair<String, String>>()
        try {
            connection?.prepareStatement(getMembersSql)?.use { ps ->
                ps.setInt(1, clanId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val uuid = rs.getString("uuid")
                    val role = rs.getString("role")
                    members.add(Pair(uuid, role))
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get members for clan $clanId: ${e.message}")
        }
        return members
    }

    fun getClanMembersByRole(clanId: Int, role: String): List<String> {
        val getMembersSql = "SELECT uuid FROM players WHERE clan_id = ? AND role = ?"
        val members = mutableListOf<String>()
        try {
            connection?.prepareStatement(getMembersSql)?.use { ps ->
                ps.setInt(1, clanId)
                ps.setString(2, role)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    members.add(rs.getString("uuid"))
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get $role members for clan $clanId: ${e.message}")
        }
        return members
    }

    fun getClanName(clanId: Int): String? {
        val getClanNameSql = "SELECT name FROM clans WHERE id = ?"
        try {
            connection?.prepareStatement(getClanNameSql)?.use { ps ->
                ps.setInt(1, clanId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return rs.getString("name")
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get clan name for clan $clanId: ${e.message}")
        }
        return null
    }

    fun addInvitation(clanId: Int, invitedUUID: String, invitedUsername: String, inviterUUID: String, inviterUsername: String): Boolean {
        val addInvitationSql = "INSERT INTO clan_invitations (clan_id, invitee_uuid, inviter_uuid, status) VALUES (?, ?, ?, 'pending')"
        try {
            connection?.prepareStatement(addInvitationSql)?.use { ps ->
                ps.setInt(1, clanId)
                ps.setString(2, invitedUUID)
                ps.setString(3, inviterUUID)
                return ps.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not add invitation: ${e.message}")
        }
        return false
    }

    fun getPlayerInvitations(playerUUID: String): List<Triple<Int, String, String>> {
        val getInvitationsSql = "SELECT i.clan_id, c.name, i.inviter_uuid FROM clan_invitations i JOIN clans c ON i.clan_id = c.id WHERE i.invitee_uuid = ? AND i.status = 'pending'"
        val invitations = mutableListOf<Triple<Int, String, String>>()
        try {
            connection?.prepareStatement(getInvitationsSql)?.use { ps ->
                ps.setString(1, playerUUID)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val clanId = rs.getInt("clan_id")
                    val clanName = rs.getString("name")
                    val inviterUUID = rs.getString("inviter_uuid")
                    invitations.add(Triple(clanId, clanName, inviterUUID))
                }
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not get invitations for player $playerUUID: ${e.message}")
        }
        return invitations
    }

    fun removeInvitation(clanId: Int, playerUUID: String): Boolean {
        val removeInvitationSql = "DELETE FROM clan_invitations WHERE clan_id = ? AND invitee_uuid = ?"
        try {
            connection?.prepareStatement(removeInvitationSql)?.use { ps ->
                ps.setInt(1, clanId)
                ps.setString(2, playerUUID)
                return ps.executeUpdate() > 0
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not remove invitation: ${e.message}")
        }
        return false
    }

    fun hasInvitation(clanId: Int, playerUUID: String): Boolean {
        val hasInvitationSql = "SELECT 1 FROM clan_invitations WHERE clan_id = ? AND invitee_uuid = ? AND status = 'pending'"
        try {
            connection?.prepareStatement(hasInvitationSql)?.use { ps ->
                ps.setInt(1, clanId)
                ps.setString(2, playerUUID)
                val rs = ps.executeQuery()
                return rs.next()
            }
        } catch (e: SQLException) {
            plugin.logger.severe("Could not check invitation: ${e.message}")
        }
        return false
    }
}
