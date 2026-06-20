package top.fraa2a.caesar.JoinEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import top.fraa2a.caesar.Caesar;
import top.fraa2a.caesar.db.DbManager;

import java.nio.charset.StandardCharsets;

public class HandleJoin implements Listener, PluginMessageListener {

    private final Caesar plugin;
    private final DbManager database;

    public HandleJoin(Caesar plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabaseManager();
        Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, "minecraft:brand", this);
    }

    public HandleJoin(JavaPlugin plugin) {
        this(requireCaesar(plugin));
    }

    private static Caesar requireCaesar(JavaPlugin plugin) {
        if (plugin instanceof Caesar caesar) {
            return caesar;
        }

        throw new IllegalArgumentException("HandleJoin richiede un'istanza di Caesar.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        database.recordJoin(event.getPlayer());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand")) return;

        String brand = readMinecraftString(message);
        database.saveClientBrand(player, brand);

        Bukkit.getLogger().info(player.getName() + " usa client brand: " + brand);
    }

    private String readMinecraftString(byte[] data) {
        int[] index = {0};
        int length = readVarInt(data, index);

        if (length < 0 || index[0] + length > data.length) {
            return new String(data, StandardCharsets.UTF_8);
        }

        return new String(data, index[0], length, StandardCharsets.UTF_8);
    }

    private int readVarInt(byte[] data, int[] index) {
        int value = 0;
        int position = 0;

        while (true) {
            if (index[0] >= data.length) return -1;

            byte currentByte = data[index[0]++];
            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) break;

            position += 7;
            if (position >= 32) return -1;
        }

        return value;
    }
}
