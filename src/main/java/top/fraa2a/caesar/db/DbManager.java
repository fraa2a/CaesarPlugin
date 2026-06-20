package top.fraa2a.caesar.db;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;

public class DbManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public DbManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public record PlayerEntry(
            UUID uuid,
            String name,
            long firstSeen,
            long lastSeen,
            int seenCount
    ) {}

    public void connect() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            File dbFile = new File(plugin.getDataFolder(), "data.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            connection = DriverManager.getConnection(url);

            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL;");
                statement.execute("PRAGMA synchronous=NORMAL;");
                statement.execute("PRAGMA foreign_keys=ON;");
                statement.execute("PRAGMA busy_timeout=5000;");
            }

            createTables();

            plugin.getLogger().info("Database SQLite connesso correttamente.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Errore durante la connessione al database.");
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        first_seen INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        seen_count INTEGER NOT NULL DEFAULT 1
                    );
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_clients (
                        uuid TEXT NOT NULL,
                        client_brand TEXT NOT NULL,
                        first_seen INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        join_count INTEGER NOT NULL DEFAULT 1,
                        
                        PRIMARY KEY (uuid, client_brand),
                        FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
                    );
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_ips (
                        uuid TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        first_seen INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        join_count INTEGER NOT NULL DEFAULT 1,
                        
                        PRIMARY KEY (uuid, ip),
                        FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
                    );
                    """);

            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_player_ips_ip
                    ON player_ips(ip);
                    """);

            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_player_clients_uuid
                    ON player_clients(uuid);
                    """);
        }
    }

    /**
     * Da chiamare nel PlayerJoinEvent.
     * Salva:
     * - player
     * - IP usato in questo join
     */
    public synchronized void recordJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = getPlayerIp(player);

        recordJoin(uuid, name, ip);
    }

    public synchronized void recordJoin(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();

        String playerSql = """
                INSERT INTO players (uuid, name, first_seen, last_seen, seen_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    last_seen = excluded.last_seen,
                    seen_count = players.seen_count + 1;
                """;

        String ipSql = """
                INSERT INTO player_ips (uuid, ip, first_seen, last_seen, join_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(uuid, ip) DO UPDATE SET
                    last_seen = excluded.last_seen,
                    join_count = player_ips.join_count + 1;
                """;

        try {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(playerSql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.executeUpdate();
            }

            if (ip != null && !ip.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(ipSql)) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, ip);
                    statement.setLong(3, now);
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
            }

            connection.commit();
        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Errore durante il salvataggio del join di " + name);
            e.printStackTrace();
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * Da chiamare quando ricevi minecraft:brand.
     * Salva ogni client diverso usato dal player.
     */
    public synchronized void saveClientBrand(Player player, String brand) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        saveClientBrand(uuid, name, brand);
    }

    public synchronized void saveClientBrand(UUID uuid, String name, String clientBrand) {
        if (clientBrand == null || clientBrand.isBlank()) {
            clientBrand = "unknown";
        }

        clientBrand = clientBrand.trim();

        long now = System.currentTimeMillis();

        String playerSql = """
                INSERT INTO players (uuid, name, first_seen, last_seen, seen_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    last_seen = excluded.last_seen;
                """;

        String clientSql = """
                INSERT INTO player_clients (uuid, client_brand, first_seen, last_seen, join_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(uuid, client_brand) DO UPDATE SET
                    last_seen = excluded.last_seen,
                    join_count = player_clients.join_count + 1;
                """;

        try {
            connection.setAutoCommit(false);

            try (PreparedStatement statement = connection.prepareStatement(playerSql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(clientSql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, clientBrand);
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.executeUpdate();
            }

            connection.commit();
        } catch (SQLException e) {
            rollback();
            plugin.getLogger().warning("Errore durante il salvataggio del client brand di " + name);
            e.printStackTrace();
        } finally {
            restoreAutoCommit();
        }
    }

    public synchronized Optional<String> getLastClientBrand(UUID uuid) {
        String sql = """
                SELECT client_brand
                FROM player_clients
                WHERE uuid = ?
                ORDER BY last_seen DESC
                LIMIT 1;
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getString("client_brand"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la lettura dell'ultimo client brand.");
            e.printStackTrace();
        }

        return Optional.empty();
    }

    public synchronized List<ClientEntry> getClientHistory(UUID uuid) {
        String sql = """
                SELECT client_brand, first_seen, last_seen, join_count
                FROM player_clients
                WHERE uuid = ?
                ORDER BY last_seen DESC;
                """;

        List<ClientEntry> entries = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new ClientEntry(
                            resultSet.getString("client_brand"),
                            resultSet.getLong("first_seen"),
                            resultSet.getLong("last_seen"),
                            resultSet.getInt("join_count")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la lettura dello storico client.");
            e.printStackTrace();
        }

        return entries;
    }

    public synchronized List<IpEntry> getIpsOfPlayer(UUID uuid) {
        String sql = """
                SELECT ip, first_seen, last_seen, join_count
                FROM player_ips
                WHERE uuid = ?
                ORDER BY last_seen DESC;
                """;

        List<IpEntry> entries = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new IpEntry(
                            resultSet.getString("ip"),
                            resultSet.getLong("first_seen"),
                            resultSet.getLong("last_seen"),
                            resultSet.getInt("join_count")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la lettura degli IP del player.");
            e.printStackTrace();
        }

        return entries;
    }

    public synchronized List<PlayerOnIpEntry> getPlayersByIp(String ip) {
        String sql = """
                SELECT p.uuid, p.name, i.first_seen, i.last_seen, i.join_count
                FROM player_ips i
                JOIN players p ON p.uuid = i.uuid
                WHERE i.ip = ?
                ORDER BY i.last_seen DESC;
                """;

        List<PlayerOnIpEntry> entries = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ip);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new PlayerOnIpEntry(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("name"),
                            resultSet.getLong("first_seen"),
                            resultSet.getLong("last_seen"),
                            resultSet.getInt("join_count")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la lettura dei player collegati all'IP " + ip);
            e.printStackTrace();
        }

        return entries;
    }

    public synchronized Map<String, List<PlayerOnIpEntry>> getAllIpLinks() {
        String sql = """
                SELECT i.ip, p.uuid, p.name, i.first_seen, i.last_seen, i.join_count
                FROM player_ips i
                JOIN players p ON p.uuid = i.uuid
                ORDER BY i.ip ASC, i.last_seen DESC;
                """;

        Map<String, List<PlayerOnIpEntry>> result = new LinkedHashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String ip = resultSet.getString("ip");

                PlayerOnIpEntry entry = new PlayerOnIpEntry(
                        UUID.fromString(resultSet.getString("uuid")),
                        resultSet.getString("name"),
                        resultSet.getLong("first_seen"),
                        resultSet.getLong("last_seen"),
                        resultSet.getInt("join_count")
                );

                result.computeIfAbsent(ip, ignored -> new ArrayList<>()).add(entry);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la lettura di tutti i collegamenti IP-player.");
            e.printStackTrace();
        }

        return result;
    }

    private String getPlayerIp(Player player) {
        InetSocketAddress address = player.getAddress();

        if (address == null || address.getAddress() == null) {
            return null;
        }

        return address.getAddress().getHostAddress();
    }

    private void rollback() {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void restoreAutoCommit() {
        try {
            if (connection != null) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        if (connection == null) return;

        try {
            connection.close();
            plugin.getLogger().info("Database SQLite chiuso.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public record ClientEntry(
            String clientBrand,
            long firstSeen,
            long lastSeen,
            int joinCount
    ) {}

    public record IpEntry(
            String ip,
            long firstSeen,
            long lastSeen,
            int joinCount
    ) {}

    public record PlayerOnIpEntry(
            UUID uuid,
            String name,
            long firstSeenOnIp,
            long lastSeenOnIp,
            int joinCountOnIp
    ) {}


    public synchronized Optional<PlayerEntry> getPlayerByName(String name) {
        String sql = """
            SELECT uuid, name, first_seen, last_seen, seen_count
            FROM players
            WHERE LOWER(name) = LOWER(?)
            ORDER BY last_seen DESC
            LIMIT 1;
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new PlayerEntry(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("name"),
                            resultSet.getLong("first_seen"),
                            resultSet.getLong("last_seen"),
                            resultSet.getInt("seen_count")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Errore durante la ricerca del player " + name);
            e.printStackTrace();
        }

        return Optional.empty();
    }
}