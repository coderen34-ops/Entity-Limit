package tr.com.koninski.limit.lobotomi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import tr.com.koninski.limit.KoninskiEntityLimit;

/**
 * Koylu lobotomisi (Purpur villager.lobotomize benzeri): araçtaki ya da sıkışmış köylülere setAware(false).
 * Takas ve yeniden stok çalışsın diye periyodik kısa "uyandırma penceresi" verilir; oyuncu yaklaşınca,
 * takas başlayınca, araçtan inince hemen tamamen uyandırılır.
 *
 * ONEMLI: setAware Paper'da KALICI (test edildi: yeniden baslatmadan sonra aware=false kalir). Bu yuzden:
 *  - Uyutulan koyluye PDC "koninski:lobotomi" isareti konur,
 *  - Chunk bosaltilmadan once koylu uyandirilir (diske hep normal kaydedilir),
 *  - Eklenti kapanirken herkes uyandirilir; cokmeden sonra acilista / chunk yuklenirken isaretliler kontrol edilir.
 */
public final class Lobotomi implements Listener {

    /** Koylu basina kucuk durum. */
    private static final class Durum {
        boolean uyutuldu;
        int sikisikTick = Integer.MIN_VALUE / 2; // Son sikisiklik degerlendirmesi
        boolean sikisik;
    }

    public static final NamespacedKey ISARET = new NamespacedKey("koninski", "lobotomi");

    private final KoninskiEntityLimit plugin;
    private LobotomiAyarlar ayar;
    private boolean calisiyor;
    private BukkitTask gorev;
    private final Map<UUID, Durum> durumlar = new HashMap<>();
    private final Deque<Chunk> kuyruk = new ArrayDeque<>();

    // Istatistik (son tam tarama)
    private long turBaslangic, sonTurMs, toplamNs, calismaSayisi;
    private int turArac, turSikisik, sonArac, sonSikisik;
    private Map<String, Integer> turAracYerleri = new HashMap<>(), sonAracYerleri = new HashMap<>();

    public Lobotomi(KoninskiEntityLimit plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.ayar = LobotomiAyarlar.oku(config);
        this.calisiyor = ayar.aktif;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Cokme sonrasi: yuklu dunyalardaki isaretli koyluler
        for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) isaretliYuklendi(v);
        baslat();
    }

    // ------------------------------------------------------------------ YASAM DONGUSU
    private void baslat() {
        if (gorev != null) gorev.cancel();
        gorev = null;
        if (!calisiyor) return;
        gorev = Bukkit.getScheduler().runTaskTimer(plugin, this::calis, ayar.taramaAraligiTick, ayar.taramaAraligiTick);
    }

    /** Reload: once dogrulanmis ayarlar uygulanir. Kapatildiysa herkes uyandirilir. */
    public void yenile(FileConfiguration config) {
        ayar = LobotomiAyarlar.oku(config);
        calisiyor = ayar.aktif;
        if (!calisiyor) hepsiniUyandir();
        kuyruk.clear();
        baslat();
    }

    /** Eklenti kapanirken: TUM uyutulmus koyluler uyandirilir. */
    public void kapat() {
        if (gorev != null) gorev.cancel();
        gorev = null;
        hepsiniUyandir();
    }

    public void ac(CommandSender s) {
        calisiyor = true;
        baslat();
        s.sendMessage("§aKöylü lobotomisi açıldı (çalışma zamanı; config değişmedi).");
    }

    public void kapatKomut(CommandSender s) {
        calisiyor = false;
        if (gorev != null) gorev.cancel();
        gorev = null;
        int n = hepsiniUyandir();
        s.sendMessage("§eKöylü lobotomisi kapatıldı; " + n + " köylü uyandırıldı.");
    }

    private int hepsiniUyandir() {
        int n = 0;
        for (UUID u : new ArrayList<>(durumlar.keySet())) {
            if (Bukkit.getEntity(u) instanceof Villager v) { if (uyandir(v)) n++; }
        }
        durumlar.clear();
        kuyruk.clear();
        // Takipte olmayan ama isaretli kalmis (yuklu) koyluler de
        for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) {
            if (v.getPersistentDataContainer().has(ISARET)) { uyandir(v); n++; }
        }
        return n;
    }

    // ------------------------------------------------------------------ ZAMANLAYICI
    private void calis() {
        long t0 = System.nanoTime();
        int simdi = Bukkit.getCurrentTick();
        hizliTur(simdi);
        yavasTur(simdi);
        toplamNs += System.nanoTime() - t0;
        calismaSayisi++;
    }

    /** Uyutulmuslar: artik aday degilse hemen uyandir; adaysa uyandirma pencerelerini planla. */
    private void hizliTur(int simdi) {
        Map<Integer, List<UUID>> pencereler = new HashMap<>();
        for (Iterator<Map.Entry<UUID, Durum>> it = durumlar.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Durum> e = it.next();
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof Villager v) || !v.isValid()) { it.remove(); continue; }
            Durum d = e.getValue();
            if (!d.uyutuldu) continue;
            if (!aday(v, d, simdi)) {
                uyandir(v);
                it.remove();
                continue;
            }
            // Pencere: her uyandirma-araligi'nda bir, UUID'den ofsetle (hepsi ayni tick'te uyanmasin)
            int aralik = ayar.uyandirmaAraligi;
            int ofset = Math.floorMod(e.getKey().hashCode(), aralik);
            for (int t = simdi + Math.floorMod(ofset - simdi, aralik); t < simdi + ayar.taramaAraligiTick; t += aralik) {
                pencereler.computeIfAbsent(Math.max(1, t - simdi), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        for (Map.Entry<Integer, List<UUID>> p : pencereler.entrySet()) {
            List<UUID> liste = p.getValue();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (UUID u : liste) {
                    Durum d = durumlar.get(u);
                    if (d != null && d.uyutuldu && Bukkit.getEntity(u) instanceof Villager v) v.setAware(true);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (UUID u : liste) {
                        Durum d = durumlar.get(u);
                        if (d != null && d.uyutuldu && Bukkit.getEntity(u) instanceof Villager v) v.setAware(false);
                    }
                }, ayar.uyanmaSuresi);
            }, p.getKey());
        }
    }

    /** Yuklu chunk'lar dilim dilim: yeni adaylar uyutulur. Chunk yuklenmez. */
    private void yavasTur(int simdi) {
        if (kuyruk.isEmpty()) {
            if (turBaslangic != 0) {
                sonTurMs = System.currentTimeMillis() - turBaslangic;
                sonArac = turArac;
                sonSikisik = turSikisik;
                sonAracYerleri = turAracYerleri;
            }
            turBaslangic = System.currentTimeMillis();
            turArac = 0;
            turSikisik = 0;
            turAracYerleri = new HashMap<>();
            for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) kuyruk.add(c);
        }
        for (int i = 0; i < ayar.taramaChunkPerTick && !kuyruk.isEmpty(); i++) {
            Chunk c = kuyruk.poll();
            if (!c.isLoaded() || !c.isEntitiesLoaded()) continue;
            for (Entity en : c.getEntities()) {
                if (!(en instanceof Villager v) || !v.isValid()) continue;
                if (v.getVehicle() != null) {
                    turArac++;
                    turAracYerleri.merge(c.getWorld().getName() + " " + v.getLocation().getBlockX() + " " + v.getLocation().getBlockY() + " " + v.getLocation().getBlockZ(), 1, Integer::sum);
                }
                Durum d = durumlar.get(v.getUniqueId());
                if (d != null && d.uyutuldu) continue; // Hizli tur ilgileniyor
                if (d == null) d = new Durum();
                if (aday(v, d, simdi)) {
                    if (v.getVehicle() == null) turSikisik++;
                    uyut(v, d);
                    durumlar.put(v.getUniqueId(), d);
                } else if (d.sikisikTick != Integer.MIN_VALUE / 2) {
                    durumlar.put(v.getUniqueId(), d); // Sikisiklik sonucu onbellekte kalsin
                }
            }
        }
    }

    // ------------------------------------------------------------------ KARAR
    private boolean korumali(Villager v) {
        if (v.customName() != null || !v.hasAI()) return true; // Isimli koylu ve NPC'ler (AI kapali)
        for (NamespacedKey k : v.getPersistentDataContainer().getKeys()) {
            if (k.equals(ISARET)) continue;
            if (ayar.herhangiPdcKoru || ayar.korumaliNamespaceler.contains(k.getNamespace())) return true;
        }
        return false;
    }

    private boolean oyuncuYakin(Villager v) {
        if (ayar.oyuncuYaricapi <= 0) return false;
        double r2 = (double) ayar.oyuncuYaricapi * ayar.oyuncuYaricapi;
        Location l = v.getLocation();
        for (Player p : v.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) <= r2) return true;
        return false;
    }

    private boolean aday(Villager v, Durum d, int simdi) {
        if (korumali(v) || v.getTrader() != null) return false;
        if (v.getProfession() == Villager.Profession.NONE && !ayar.meslegiOlmayanlar) return false;
        if (oyuncuYakin(v)) return false;
        if (ayar.aracIciAday && v.getVehicle() != null) return true;
        if (!ayar.sikisikAday || v.getVehicle() != null) return false;
        if (simdi - d.sikisikTick >= ayar.degerlendirmeAraligi) {
            d.sikisik = sikisik(v);
            d.sikisikTick = simdi;
        }
        return d.sikisik;
    }

    /**
     * Ucuz sikisiklik kontrolu (pathfinding yok): ayaklarinin hizasindaki 4 yatay komsudan hicbirine yurunemiyorsa.
     * Komsu yuklenmemis bir chunk'taysa o bloga HIC erisilmez ve sikisik sayilmaz (chunk yuklenmez).
     */
    private boolean sikisik(Villager v) {
        World w = v.getWorld();
        Block ayak = v.getLocation().getBlock();
        boolean basUstuAcik = gecilir(ayak.getRelative(0, 2, 0));
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            int bx = ayak.getX() + f.getModX(), bz = ayak.getZ() + f.getModZ();
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            Block n = ayak.getRelative(f);
            Block nUst = n.getRelative(0, 1, 0);
            // Duz yurume (ya da asagi inme): komsu ve ustu bos
            if (gecilir(n) && gecilir(nUst)) return false;
            // Bir blok tirmanma: komsu katı (cit/duvar degil), ustundeki iki blok ve kendi basinin ustu bos
            if (n.getType().isSolid() && !yuksekEngel(n.getType()) && gecilir(nUst) && gecilir(nUst.getRelative(0, 1, 0)) && basUstuAcik) return false;
        }
        return true;
    }

    private static boolean gecilir(Block b) {
        return b.isPassable() && !b.isLiquid() || b.getType() == Material.WATER;
    }

    /** Uzerine cikilamayan (1,5 blok) engeller. */
    private static boolean yuksekEngel(Material m) {
        return Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
    }

    // ------------------------------------------------------------------ UYUT / UYANDIR
    private void uyut(Villager v, Durum d) {
        v.getPersistentDataContainer().set(ISARET, PersistentDataType.BYTE, (byte) 1);
        v.setAware(false);
        d.uyutuldu = true;
    }

    /** Tamamen uyandirir ve isareti kaldirir. Uyutulmus idiyse true. */
    private boolean uyandir(Villager v) {
        boolean vardi = v.getPersistentDataContainer().has(ISARET);
        v.getPersistentDataContainer().remove(ISARET);
        v.setAware(true);
        Durum d = durumlar.get(v.getUniqueId());
        if (d != null) d.uyutuldu = false;
        return vardi;
    }

    /** Isaretli koylu yuklendi (cokme sonrasi ya da onceki surumden): calismiyorsa uyandir, calisiyorsa takibe al. */
    private void isaretliYuklendi(Villager v) {
        if (!v.getPersistentDataContainer().has(ISARET)) return;
        if (!calisiyor) { uyandir(v); return; }
        Durum d = new Durum();
        d.uyutuldu = true;
        durumlar.put(v.getUniqueId(), d); // Hizli tur aday degilse hemen uyandirir
    }

    @EventHandler
    public void onYukle(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) if (e instanceof Villager v) isaretliYuklendi(v);
    }

    /** Chunk bosaltilmadan once uyandir: diske hep normal (aware=true, isaretsiz) kaydedilsin. */
    @EventHandler
    public void onBosalt(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (!(e instanceof Villager v)) continue;
            Durum d = durumlar.remove(v.getUniqueId());
            if ((d != null && d.uyutuldu) || v.getPersistentDataContainer().has(ISARET)) {
                v.getPersistentDataContainer().remove(ISARET);
                v.setAware(true);
            }
        }
    }

    // ------------------------------------------------------------------ KOMUTLAR
    public void komut(CommandSender s, String[] args, Runnable reload) {
        if (!s.hasPermission(tr.com.koninski.limit.optimize.Optimize.YETKI)) { s.sendMessage("§cYetkin yok."); return; }
        String alt = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "durum";
        switch (alt) {
            case "ac", "aç" -> ac(s);
            case "kapat" -> kapatKomut(s);
            case "reload" -> reload.run();
            case "rapor" -> rapor(s);
            case "durum" -> durum(s);
            default -> s.sendMessage("§eKullanım: §f/lobotomi <durum|rapor|ac|kapat|reload>");
        }
    }

    private int uyutulmusSayisi() {
        int n = 0;
        for (Durum d : durumlar.values()) if (d.uyutuldu) n++;
        return n;
    }

    private void durum(CommandSender s) {
        s.sendMessage("§6§l=== Köylü Lobotomisi ===");
        s.sendMessage("§7Durum: " + (calisiyor ? "§açalışıyor" : "§ckapalı") + " §7| Uyutulmuş köylü: §f" + uyutulmusSayisi()
                + " §7| Son taramada araçta: §f" + sonArac + "§7, sıkışık (yeni uyutulan): §f" + sonSikisik);
        double ortMs = calismaSayisi == 0 ? 0 : toplamNs / 1e6 / calismaSayisi;
        s.sendMessage("§7Son tam tarama: §f" + (sonTurMs / 1000.0) + " sn §7| Çalışma başı ortalama maliyet: §f" + String.format(Locale.US, "%.3f", ortMs) + " ms");
        s.sendMessage("§7Ayarlar: tarama " + ayar.taramaAraligiTick + " tick, " + ayar.taramaChunkPerTick + " chunk/çalışma; oyuncu yarıçapı " + ayar.oyuncuYaricapi
                + "; uyanma " + ayar.uyanmaSuresi + "/" + ayar.uyandirmaAraligi + " tick; araç " + (ayar.aracIciAday ? "evet" : "hayır")
                + ", sıkışık " + (ayar.sikisikAday ? "evet" : "hayır") + ", mesleksiz " + (ayar.meslegiOlmayanlar ? "evet" : "hayır"));
    }

    private void rapor(CommandSender s) {
        s.sendMessage("§6Araçtaki köylüler (son tarama, en kalabalık 10 nokta):");
        if (sonAracYerleri.isEmpty()) s.sendMessage("§7 - (tarama henüz bitmedi ya da yok)");
        sonAracYerleri.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10).forEach(e -> {
            String[] p = e.getKey().split(" ");
            s.sendMessage("§7 " + p[0] + " " + p[1] + ", " + p[3] + ": §f" + e.getValue() + " §8/tp @s " + p[1] + " " + p[2] + " " + p[3]);
        });
        Map<String, Integer> dunya = new HashMap<>();
        for (Map.Entry<UUID, Durum> e : durumlar.entrySet()) {
            if (e.getValue().uyutuldu && Bukkit.getEntity(e.getKey()) instanceof Villager v) dunya.merge(v.getWorld().getName(), 1, Integer::sum);
        }
        s.sendMessage("§6Uyutulmuş köylüler (dünya başına): §f" + (dunya.isEmpty() ? "-" : dunya.toString()));
    }

    public List<String> tamamla(String[] args) {
        List<String> o = new ArrayList<>();
        if (args.length == 1) for (String x : List.of("durum", "rapor", "ac", "kapat", "reload")) if (x.startsWith(args[0].toLowerCase(Locale.ROOT))) o.add(x);
        return o;
    }
}
