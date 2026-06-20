package top.fraa2a.caesar;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import top.fraa2a.caesar.JoinEvent.HandleJoin;
import top.fraa2a.caesar.db.DbManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Caesar extends JavaPlugin {

    private DbManager databaseManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    public File staffDataFile;
    public FileConfiguration staffDataConfig;

    private final Map<UUID, BukkitTask> staffmodeActionbarTasks = new HashMap<>();

    @Override
    public void onEnable() {
        databaseManager = new DbManager(this);
        databaseManager.connect();

        Bukkit.getPluginManager().registerEvents(new HandleJoin(this), this);

        Objects.requireNonNull(getCommand("alts")).setExecutor(this);
        Objects.requireNonNull(getCommand("check")).setExecutor(this);
        Objects.requireNonNull(getCommand("staffmode")).setExecutor(this);

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        staffDataFile = new File(getDataFolder(), "data.yml");

        if (!staffDataFile.exists()) {
            try {
                staffDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        staffDataConfig = YamlConfiguration.loadConfiguration(staffDataFile);

        getLogger().info("Caesar abilitato.");
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this);

        for (BukkitTask task : staffmodeActionbarTasks.values()) {
            task.cancel();
        }

        staffmodeActionbarTasks.clear();

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("Caesar disabilitato.");
    }

    public DbManager getDatabaseManager() {
        return databaseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        String cmd = command.getName().toLowerCase(Locale.ROOT);

        switch (cmd) {
            case "alts" -> {
                return handleAlts(sender, args);
            }

            case "check" -> {
                return handleCheck(sender, args);
            }

            case "staffmode" -> {
                return handleStaffmode(sender, args);
            }

            case "sm" -> {
                return handleStaffmode(sender, args);
            }

            default -> {
                return false;
            }
        }
    }

    private boolean handleStaffmode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cSolo i player possono usare questo comando.");
            return true;
        }

        if (!sender.hasPermission("caesar.staff")) {
            sender.sendMessage("§cRitenta e sarai piu' fortunato.");
            return true;
        }

        if (isInStaffmode(player.getUniqueId())) {
            if (disableStaffmode(player)) {
                player.sendMessage("Staff Mode: §b§cOFF");
            }
        } else {
            if (enableStaffmode(player)) {
                player.sendMessage("Staff Mode: §b§aON");
            }
        }

        return true;
    }

    private boolean isInStaffmode(UUID uuid) {
        return staffDataConfig.getBoolean(uuid.toString() + ".in_staffmode", false);
    }

    private boolean enableStaffmode(Player player) {
        String uuid = player.getUniqueId().toString();

        ItemStack[] inventory = player.getInventory().getContents();
        ItemStack[] armor = player.getInventory().getArmorContents();

        Location location = player.getLocation();

        staffDataConfig.set(uuid + ".inventory", serializeItemStackArray(inventory));
        staffDataConfig.set(uuid + ".armor", serializeItemStackArray(armor));
        staffDataConfig.set(uuid + ".location", location);
        staffDataConfig.set(uuid + ".gamemode", player.getGameMode().name());
        staffDataConfig.set(uuid + ".in_staffmode", true);

        try {
            staffDataConfig.save(staffDataFile);
        } catch (IOException e) {
            e.printStackTrace();
            player.sendMessage("§cErrore nel salvataggio dello staffmode.");
            return false;
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        player.setGameMode(GameMode.CREATIVE);
        player.setOp(true);
        setVanish(player, true);

        startStaffmodeActionbar(player);

        return true;
    }

    private boolean disableStaffmode(Player player) {
        String uuid = player.getUniqueId().toString();

        String inventoryData = staffDataConfig.getString(uuid + ".inventory");
        String armorData = staffDataConfig.getString(uuid + ".armor");
        Location location = staffDataConfig.getLocation(uuid + ".location");
        String gamemodeName = staffDataConfig.getString(uuid + ".gamemode");

        if (inventoryData == null || inventoryData.isEmpty()) {
            player.sendMessage("§cErrore: inventario staffmode non trovato nel data.yml.");
            return false;
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        clearEffects(player);

        ItemStack[] inventory = deserializeItemStackArray(
                inventoryData,
                player.getInventory().getContents().length
        );

        player.getInventory().setContents(inventory);

        if (armorData != null && !armorData.isEmpty()) {
            ItemStack[] armor = deserializeItemStackArray(
                    armorData,
                    player.getInventory().getArmorContents().length
            );

            player.getInventory().setArmorContents(armor);
        }

        if (location != null) {
            player.teleport(location);
        }

        if (gamemodeName != null) {
            try {
                player.setGameMode(GameMode.valueOf(gamemodeName));
            } catch (IllegalArgumentException e) {
                player.setGameMode(GameMode.SURVIVAL);
            }
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }

        player.setOp(false);
        setVanish(player, false);

        stopStaffmodeActionbar(player.getUniqueId());

        staffDataConfig.set(uuid + ".in_staffmode", false);
        staffDataConfig.set(uuid + ".inventory", null);
        staffDataConfig.set(uuid + ".armor", null);
        staffDataConfig.set(uuid + ".location", null);
        staffDataConfig.set(uuid + ".gamemode", null);

        try {
            staffDataConfig.save(staffDataFile);
        } catch (IOException e) {
            e.printStackTrace();
            player.sendMessage("§cErrore nel salvataggio dello staffmode.");
            return false;
        }

        return true;
    }

    private String serializeItemStackArray(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);

            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();

            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private ItemStack[] deserializeItemStackArray(String data, int expectedSize) {
        ItemStack[] fixedItems = new ItemStack[expectedSize];

        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int savedSize = dataInput.readInt();

            for (int i = 0; i < savedSize; i++) {
                ItemStack item = (ItemStack) dataInput.readObject();

                if (i < expectedSize) {
                    fixedItems[i] = item;
                }
            }

            dataInput.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fixedItems;
    }

    private void startStaffmodeActionbar(Player player) {
        UUID uuid = player.getUniqueId();

        stopStaffmodeActionbar(uuid);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isInStaffmode(uuid)) {
                    stopStaffmodeActionbar(uuid);
                    return;
                }

                player.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        new TextComponent("§b§lsᴇɪ ɪɴ sᴛᴀғғᴍᴏᴅᴇ")
                );
            }
        }.runTaskTimer(this, 0L, 20L);

        staffmodeActionbarTasks.put(uuid, task);
    }

    private void stopStaffmodeActionbar(UUID uuid) {
        BukkitTask task = staffmodeActionbarTasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    private void clearEffects(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private boolean setVanish(Player player, boolean status) {
        ConsoleCommandSender console = Bukkit.getConsoleSender();

        String comando = "vanish " + player.getName() + " " + (status ? "true" : "false");

        Bukkit.dispatchCommand(console, comando);

        return true;
    }

    private String formatDate(long millis) {
        return dateFormat.format(new Date(millis));
    }

    private boolean handleCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("caesar.check")) {
            sender.sendMessage("§cNon hai il permesso.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cUso corretto: /check <player>");
            return true;
        }

        String targetName = args[0];

        Optional<DbManager.PlayerEntry> targetOptional =
                databaseManager.getPlayerByName(targetName);

        if (targetOptional.isEmpty()) {
            sender.sendMessage("§cPlayer non trovato nel database.");
            return true;
        }

        DbManager.PlayerEntry target = targetOptional.get();

        List<DbManager.ClientEntry> clients =
                databaseManager.getClientHistory(target.uuid());

        List<DbManager.IpEntry> ips =
                databaseManager.getIpsOfPlayer(target.uuid());

        sender.sendMessage("§8§m--------------------------------");
        sender.sendMessage("§eCheck di §f" + target.name());
        sender.sendMessage("§8UUID: §7" + target.uuid());
        sender.sendMessage("§8Primo join: §7" + formatDate(target.firstSeen()));
        sender.sendMessage("§8Ultimo join: §7" + formatDate(target.lastSeen()));
        sender.sendMessage("§8Join totali: §7" + target.seenCount());

        sender.sendMessage("");
        sender.sendMessage("§eClient usati:");

        if (clients.isEmpty()) {
            sender.sendMessage("§7- Nessun client salvato.");
        } else {
            for (DbManager.ClientEntry client : clients) {
                sender.sendMessage("§7- §f" + client.clientBrand()
                        + " §8| join: §7" + client.joinCount()
                        + " §8| prima volta: §7" + formatDate(client.firstSeen())
                        + " §8| ultima volta: §7" + formatDate(client.lastSeen()));
            }
        }

        sender.sendMessage("");
        sender.sendMessage("§eIP usati:");

        if (ips.isEmpty()) {
            sender.sendMessage("§7- Nessun IP salvato.");
        } else {
            for (DbManager.IpEntry ip : ips) {
                sender.sendMessage("§7- §f" + ip.ip()
                        + " §8| join: §7" + ip.joinCount()
                        + " §8| prima volta: §7" + formatDate(ip.firstSeen())
                        + " §8| ultima volta: §7" + formatDate(ip.lastSeen()));
            }
        }

        sender.sendMessage("§8§m--------------------------------");
        return true;
    }

    private boolean handleAlts(CommandSender sender, String[] args) {
        if (!sender.hasPermission("caesar.alts")) {
            sender.sendMessage("§cNon hai il permesso.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cUso corretto: /alts <player>");
            return true;
        }

        String targetName = args[0];

        Optional<DbManager.PlayerEntry> targetOptional =
                databaseManager.getPlayerByName(targetName);

        if (targetOptional.isEmpty()) {
            sender.sendMessage("§cPlayer non trovato nel database.");
            return true;
        }

        DbManager.PlayerEntry target = targetOptional.get();

        List<DbManager.IpEntry> targetIps =
                databaseManager.getIpsOfPlayer(target.uuid());

        if (targetIps.isEmpty()) {
            sender.sendMessage("§cNessun IP salvato per " + target.name() + ".");
            return true;
        }

        sender.sendMessage("§8§m--------------------------------");
        sender.sendMessage("§ePossibili alt di §f" + target.name());
        sender.sendMessage("§8UUID: §7" + target.uuid());
        sender.sendMessage("§8§m--------------------------------");

        Set<UUID> shownPlayers = new HashSet<>();
        shownPlayers.add(target.uuid());

        boolean foundAlts = false;

        for (DbManager.IpEntry ipEntry : targetIps) {
            String ip = ipEntry.ip();

            List<DbManager.PlayerOnIpEntry> playersOnIp =
                    databaseManager.getPlayersByIp(ip);

            sender.sendMessage("");
            sender.sendMessage("§eIP: §f" + ip
                    + " §8| usato da §7" + playersOnIp.size() + " player"
                    + " §8| ultima volta target: §7" + formatDate(ipEntry.lastSeen()));

            for (DbManager.PlayerOnIpEntry entry : playersOnIp) {
                if (entry.uuid().equals(target.uuid())) {
                    continue;
                }

                foundAlts = true;

                boolean alreadyShown = shownPlayers.contains(entry.uuid());
                shownPlayers.add(entry.uuid());

                sender.sendMessage("§7- §c" + entry.name()
                        + " §8| UUID: §7" + entry.uuid()
                        + " §8| join su questo IP: §7" + entry.joinCountOnIp()
                        + " §8| ultima volta: §7" + formatDate(entry.lastSeenOnIp())
                        + (alreadyShown ? " §8(già visto su un altro IP)" : ""));
            }
        }

        if (!foundAlts) {
            sender.sendMessage("");
            sender.sendMessage("§aNessun possibile alt trovato per " + target.name() + ".");
        }

        sender.sendMessage("§8§m--------------------------------");
        return true;
    }
}