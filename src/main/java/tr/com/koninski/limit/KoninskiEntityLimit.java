package tr.com.koninski.limit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Synchronous creation checks only; no repeating world scans or entity deletion. */
public final class KoninskiEntityLimit extends JavaPlugin implements Listener {
    private record Group(String id, String label, int chunkLimit, int areaLimit,
                         Set<String> types, List<String> suffixes) {
        boolean matches(Entity entity) {
            String type = entity.getType().name();
            return types.contains(type) || suffixes.stream().anyMatch(type::endsWith);
        }
    }
    private record Counts(int chunk, int area) {}
    private record Rejection(Group group, Counts counts, boolean areaLimit) {}
    private List<Group> groups = List.of();
    private Set<String> ignoredReasons = Set.of();
    private final Map<UUID, Long> messages = new HashMap<>();
    private boolean enabled;
    private long cooldown;
    private long blockedSpawns;
    private long blockedBreeds;
    private long blockedPlacements;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            readSettings(getConfig());
        } catch (IllegalArgumentException ex) {
            getLogger().severe("Geçersiz config: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Yeni üretim sınırları etkin. Mevcut entityler silinmez; taşıma engellenmez.");
    }

    private void readSettings(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("groups");
        if (section == null) throw new IllegalArgumentException("groups bulunamadı");
        List<Group> parsed = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String base = "groups." + id + ".";
            int chunk = config.getInt(base + "per-chunk", -1);
            int area = config.getInt(base + "per-area", -1);
            if (chunk < 1 || area < chunk) {
                throw new IllegalArgumentException(id + ": per-chunk >= 1 ve per-area >= per-chunk olmalı");
            }
            Set<String> types = new HashSet<>();
            for (String type : config.getStringList(base + "types")) {
                types.add(type.toUpperCase(Locale.ROOT));
            }
            List<String> suffixes = config.getStringList(base + "suffixes").stream()
                    .map(s -> s.toUpperCase(Locale.ROOT)).toList();
            if (types.isEmpty() && suffixes.isEmpty()) {
                throw new IllegalArgumentException(id + ": tür listesi boş");
            }
            parsed.add(new Group(id, config.getString(base + "label", id),
                    chunk, area, Set.copyOf(types), suffixes));
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("En az bir grup gerekli");
        Set<String> reasons = new HashSet<>();
        for (String reason : config.getStringList("ignored-spawn-reasons")) {
            reasons.add(reason.toUpperCase(Locale.ROOT));
        }
        long seconds = config.getLong("message-cooldown-seconds", 3);
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Mesaj süresi 1–60 olmalı");
        // Commit only after all values validate; failed reload retains previous policy.
        groups = List.copyOf(parsed);
        ignoredReasons = Set.copyOf(reasons);
        enabled = config.getBoolean("enabled", true);
        cooldown = seconds * 1000;
    }

    private Counts count(Location location, Group group, UUID exclude) {
        World world = location.getWorld();
        if (world == null) return new Counts(0, 0);
        int cx = location.getBlockX() >> 4;
        int cz = location.getBlockZ() >> 4;
        int local = 0;
        int area = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                // Never force chunk/entity loading for a limit check.
                if (!world.isChunkLoaded(x, z) || !world.getChunkAt(x, z).isEntitiesLoaded()) continue;
                for (Entity entity : world.getChunkAt(x, z).getEntities()) {
                    if (entity.isDead() || entity.getUniqueId().equals(exclude) || !group.matches(entity)) continue;
                    area++;
                    if (dx == 0 && dz == 0) local++;
                }
            }
        }
        return new Counts(local, area);
    }

    private Rejection reject(Entity candidate) {
        if (!enabled) return null;
        for (Group group : groups) {
            if (!group.matches(candidate)) continue;
            Counts counts = count(candidate.getLocation(), group, candidate.getUniqueId());
            if (counts.chunk >= group.chunkLimit) return new Rejection(group, counts, false);
            if (counts.area >= group.areaLimit) return new Rejection(group, counts, true);
        }
        return null;
    }

    private void notifyPlayer(Player player, Rejection rejection) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        if (now - messages.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;
        messages.put(player.getUniqueId(), now);
        String scope = rejection.areaLimit ? "çevredeki 3x3 chunk" : "bu chunk";
        int limit = rejection.areaLimit ? rejection.group.areaLimit : rejection.group.chunkLimit;
        player.sendMessage("§e" + rejection.group.label + " sınırı dolu: " + scope
                + " için " + limit + ". Yeni üretim engellendi. /entitylimit ile sayıları görebilirsin.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (ignoredReasons.contains(event.getSpawnReason().name())) return;
        Rejection rejection = reject(event.getEntity());
        if (rejection == null) return;
        event.setCancelled(true);
        blockedSpawns++;
        // Spawn events lack the actual player; do not blame a random nearby player.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        Rejection rejection = reject(event.getEntity());
        if (rejection == null) return;
        event.setCancelled(true);
        blockedBreeds++;
        if (event.getBreeder() instanceof Player player) notifyPlayer(player, rejection);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        Rejection rejection = reject(event.getEntity());
        if (rejection == null) return;
        event.setCancelled(true);
        blockedPlacements++;
        notifyPlayer(event.getPlayer(), rejection);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        messages.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("koninski.entitylimit.admin")) {
                sender.sendMessage("§cYetkin yok.");
                return true;
            }
            try {
                YamlConfiguration loaded = new YamlConfiguration();
                loaded.load(new File(getDataFolder(), "config.yml"));
                readSettings(loaded);
                sender.sendMessage("§aEntity sınırları yenilendi. Etkin: " + enabled);
            } catch (Exception ex) {
                sender.sendMessage("§cAyarlar geçersiz; önceki sınırlar korundu: " + ex.getMessage());
            }
            return true;
        }
        if (args.length != 0) return false;
        sender.sendMessage("EntityLimit etkin: " + enabled + "; engellenen spawn/üreme/yerleştirme: "
                + blockedSpawns + "/" + blockedBreeds + "/" + blockedPlacements);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Chunk sayılarını görmek için komutu oyunda kullan.");
            return true;
        }
        Location location = player.getLocation();
        sender.sendMessage("Chunk: " + (location.getBlockX() >> 4) + ", " + (location.getBlockZ() >> 4));
        for (Group group : groups) {
            Counts counts = count(location, group, null);
            sender.sendMessage(group.label + ": chunk " + counts.chunk + "/" + group.chunkLimit
                    + "; yüklü 3x3 bölge " + counts.area + "/" + group.areaLimit);
        }
        return true;
    }
}
