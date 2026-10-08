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

/**
 * Synchronous creation checks; no entity deletion here.
 * 1.1.0: optimizasyon araci (rapor, blok limiti, guvenli temizlik, yuk modu) tr.com.koninski.limit.optimize paketinde.
 */
public final class KoninskiEntityLimit extends JavaPlugin implements Listener {
    /**
     * aktif: grup kapatilabilir (yoksa true). reasons: bos degilse SADECE bu spawn sebeplerinde uygulanir.
     * groupIgnored: bu grup icin ek yok sayilan sebepler. Eski gruplarda bu anahtarlar yok, davranis aynen kalir.
     */
    public record Group(String id, String label, int chunkLimit, int areaLimit,
                        Set<String> types, List<String> suffixes,
                        boolean aktif, Set<String> reasons, Set<String> groupIgnored) {
        public boolean matches(Entity entity) {
            String type = entity.getType().name();
            return types.contains(type) || suffixes.stream().anyMatch(type::endsWith);
        }

        /** reason null: ureme/yerlestirme; sebep listesi olan gruplar bunlara uygulanmaz. */
        boolean appliesTo(String reason) {
            if (!aktif) return false;
            if (reason == null) return reasons.isEmpty();
            if (groupIgnored.contains(reason)) return false;
            return reasons.isEmpty() || reasons.contains(reason);
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
    private tr.com.koninski.limit.optimize.Optimize optimize;
    private tr.com.koninski.limit.lobotomi.Lobotomi lobotomi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configYukselt();
        try {
            readSettings(getConfig());
            optimize = new tr.com.koninski.limit.optimize.Optimize(this, getConfig());
            lobotomi = new tr.com.koninski.limit.lobotomi.Lobotomi(this, getConfig());
        } catch (IllegalArgumentException ex) {
            getLogger().severe(ascii("Geçersiz config: " + ex.getMessage()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(ascii("Yeni üretim sınırları etkin. Mevcut entityler silinmez; taşıma engellenmez."));
    }

    @Override
    public void onDisable() {
        // setAware kalici oldugu icin uyutulan TUM koyluler kapanirken uyandirilir
        if (lobotomi != null) lobotomi.kapat();
        if (optimize != null) optimize.kapat();
    }

    /**
     * 1.1.0 yukseltmesi (bir kez): eski config'e yeni gruplar ve optimizasyon bolumleri eklenir.
     * Mevcut degerlere dokunulmaz; config-surum 2 olunca bir daha calismaz (silinen grup geri gelmez).
     */
    private void configYukselt() {
        FileConfiguration c = getConfig();
        int surum = c.isSet("config-surum") ? c.getInt("config-surum") : 1;
        if (surum >= 3) return;
        org.bukkit.configuration.Configuration v = c.getDefaults();
        if (v == null) return;
        int eklenen = 0;
        if (surum < 2) {
        // isSet: sadece dosyadaki degerlere bakar (contains jar varsayilanlarini da sayar)
        for (String g : List.of("canavarlar", "ambient", "su", "diger_hayvanlar")) {
            if (!c.isSet("groups." + g) && v.isConfigurationSection("groups." + g)) {
                c.createSection("groups." + g, v.getConfigurationSection("groups." + g).getValues(true));
                eklenen++;
            }
        }
        for (String b : List.of("optimize", "yerdeki-esya", "blok-limitleri", "temizlik", "yuk-modu")) {
            if (!c.isSet(b) && v.isConfigurationSection(b)) {
                c.createSection(b, v.getConfigurationSection(b).getValues(true));
                eklenen++;
            }
        }
        }
        // 1.2.0: koylu lobotomisi
        if (!c.isSet("lobotomi") && v.isConfigurationSection("lobotomi")) {
            c.createSection("lobotomi", v.getConfigurationSection("lobotomi").getValues(true));
            eklenen++;
        }
        c.set("config-surum", 3);
        saveConfig();
        getLogger().info("Config 1.2.0 surumune yukseltildi (" + eklenen + " yeni bolum eklendi, mevcut degerler korundu).");
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
            Set<String> reasons = new HashSet<>();
            for (String r : config.getStringList(base + "spawn-reasons")) reasons.add(r.toUpperCase(Locale.ROOT));
            Set<String> groupIgnored = new HashSet<>();
            for (String r : config.getStringList(base + "ignored-spawn-reasons")) groupIgnored.add(r.toUpperCase(Locale.ROOT));
            for (String t : types) {
                if (!GECERLI_TURLER.contains(t)) getLogger().warning(ascii("Grup " + id + ": bilinmeyen entity turu " + t + " (bu surumde yok, eslesmez)"));
            }
            parsed.add(new Group(id, config.getString(base + "label", id),
                    chunk, area, Set.copyOf(types), suffixes,
                    config.getBoolean(base + "aktif", true), Set.copyOf(reasons), Set.copyOf(groupIgnored)));
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

    private static final Set<String> GECERLI_TURLER = new HashSet<>();
    static {
        for (org.bukkit.entity.EntityType t : org.bukkit.entity.EntityType.values()) GECERLI_TURLER.add(t.name());
    }

    /** Konsol icin ASCII-guvenli metin (bazi konsollar Turkce harfleri ? gosterir). */
    public static String ascii(String s) {
        return s.replace('ç', 'c').replace('Ç', 'C').replace('ğ', 'g').replace('Ğ', 'G').replace('ı', 'i').replace('İ', 'I')
                .replace('ö', 'o').replace('Ö', 'O').replace('ş', 's').replace('Ş', 'S').replace('ü', 'u').replace('Ü', 'U')
                .replace('–', '-');
    }

    public List<Group> groups() { return groups; }
    public Set<String> ignoredReasons() { return ignoredReasons; }
    public boolean limitsEnabled() { return enabled; }
    public long[] blockedCounts() { return new long[]{blockedSpawns, blockedBreeds, blockedPlacements}; }

    /** Tek chunk'taki eslesen entity sayisi (yuklu degilse 0; chunk yuklemez). */
    private int countChunk(World world, int x, int z, Group group, UUID exclude) {
        if (!world.isChunkLoaded(x, z) || !world.getChunkAt(x, z).isEntitiesLoaded()) return 0;
        int n = 0;
        for (Entity entity : world.getChunkAt(x, z).getEntities()) {
            if (entity.isDead() || entity.getUniqueId().equals(exclude) || !group.matches(entity)) continue;
            n++;
        }
        return n;
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
        return reject(candidate, null);
    }

    /**
     * Ucuz kontrol once (tur ve sebep eslesmesi); sayim sadece eslesen grupta. Kendi chunk'i doluysa
     * 3x3 bolge hic sayilmaz (sonuc ayni, maliyet daha dusuk).
     */
    private Rejection reject(Entity candidate, String reason) {
        if (!enabled) return null;
        for (Group group : groups) {
            if (!group.matches(candidate) || !group.appliesTo(reason)) continue;
            Location loc = candidate.getLocation();
            World world = loc.getWorld();
            if (world == null) continue;
            int local = countChunk(world, loc.getBlockX() >> 4, loc.getBlockZ() >> 4, group, candidate.getUniqueId());
            if (local >= group.chunkLimit) return new Rejection(group, new Counts(local, local), false);
            Counts counts = count(loc, group, candidate.getUniqueId());
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
        Rejection rejection = reject(event.getEntity(), event.getSpawnReason().name());
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

    /** /entitylimit reload ve /optimize reload: once dogrulanir, gecersizse onceki ayarlar korunur. */
    private void yenile(CommandSender sender) {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(new File(getDataFolder(), "config.yml"));
            // Iki parca da once dogrulanir: optimize ayari gecersizse limitler de degismez
            tr.com.koninski.limit.optimize.OptimizeAyarlar.oku(loaded);
            tr.com.koninski.limit.lobotomi.LobotomiAyarlar.oku(loaded);
            readSettings(loaded);
            if (optimize != null) optimize.yenile(loaded);
            if (lobotomi != null) lobotomi.yenile(loaded);
            sender.sendMessage("§aEntity sınırları yenilendi. Etkin: " + enabled);
        } catch (Exception ex) {
            sender.sendMessage("§cAyarlar geçersiz; önceki sınırlar korundu: " + ex.getMessage());
        }
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("optimize") && optimize != null && sender.hasPermission(tr.com.koninski.limit.optimize.Optimize.YETKI)) {
            return optimize.tamamla(args);
        }
        if (command.getName().equalsIgnoreCase("lobotomi") && lobotomi != null && sender.hasPermission(tr.com.koninski.limit.optimize.Optimize.YETKI)) {
            return lobotomi.tamamla(args);
        }
        if (command.getName().equalsIgnoreCase("entitylimit") && args.length == 1) return java.util.List.of("reload");
        return java.util.List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lobotomi")) {
            if (lobotomi != null) lobotomi.komut(sender, args, () -> yenile(sender));
            return true;
        }
        if (command.getName().equalsIgnoreCase("optimize")) {
            if (optimize == null) return true;
            return optimize.komut(sender, args, () -> yenile(sender));
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("koninski.entitylimit.admin")) {
                sender.sendMessage("§cYetkin yok.");
                return true;
            }
            yenile(sender);
            return true;
        }
        if (args.length != 0) return false;
        // 1.1.0: sayilari gormek de yetki ister (onceden herkes bakabiliyordu)
        if (!sender.hasPermission(tr.com.koninski.limit.optimize.Optimize.YETKI) && !sender.hasPermission("koninski.entitylimit.admin")) {
            sender.sendMessage("§cYetkin yok.");
            return true;
        }
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
