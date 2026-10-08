package tr.com.koninski.limit.optimize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import tr.com.koninski.limit.KoninskiEntityLimit;

/**
 * Sunucu optimizasyon araci: /optimize rapor | temizle | durum | reload.
 * Yetki: koninski.optimize.admin (varsayilan op).
 */
public final class Optimize {

    public static final String YETKI = "koninski.optimize.admin";

    private final KoninskiEntityLimit ana;
    private OptimizeAyarlar ayar;
    private final OptimizeLog log;
    private final Tarayici tarayici;
    private final Rapor rapor;
    private final Temizlik temizlik;
    private final YukModu yuk;

    public Optimize(KoninskiEntityLimit ana, FileConfiguration config) {
        this.ana = ana;
        this.ayar = OptimizeAyarlar.oku(config); // Gecersizse istisna; ana sinif eklentiyi kapatir
        this.log = new OptimizeLog(ana);
        this.tarayici = new Tarayici(ana);
        this.rapor = new Rapor(this);
        this.temizlik = new Temizlik(this);
        this.yuk = new YukModu(this);
        Bukkit.getPluginManager().registerEvents(new BlokLimit(this), ana);
        Bukkit.getPluginManager().registerEvents(yuk, ana);
        yuk.kur();
    }

    KoninskiEntityLimit ana() { return ana; }
    OptimizeAyarlar ayar() { return ayar; }
    OptimizeLog log() { return log; }
    Tarayici tarayici() { return tarayici; }

    /** Reload: once dogrulanir, gecerliyse uygulanir (gecersizse eski ayarlar kalir, istisna firlatilir). */
    public void yenile(FileConfiguration config) {
        OptimizeAyarlar yeni = OptimizeAyarlar.oku(config);
        this.ayar = yeni;
        yuk.kur();
    }

    public void kapat() {
        tarayici.iptal();
        yuk.durdur();
        log.kapat();
    }

    // ------------------------------------------------------------------ KOMUT
    /** @return komut islendiyse true */
    public boolean komut(CommandSender s, String[] args, Runnable reload) {
        if (!s.hasPermission(YETKI)) {
            s.sendMessage("§cYetkin yok.");
            return true;
        }
        String alt = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "durum";
        switch (alt) {
            case "rapor" -> {
                List<World> dunyalar = new ArrayList<>();
                if (args.length > 1) {
                    World w = Bukkit.getWorld(args[1]);
                    if (w == null) { s.sendMessage("§cBöyle bir dünya yok: " + args[1]); return true; }
                    dunyalar.add(w);
                } else dunyalar.addAll(Bukkit.getWorlds());
                rapor.baslat(s, dunyalar);
            }
            case "temizle" -> {
                if (args.length < 2) {
                    s.sendMessage("§eKullanım: §f/optimize temizle <" + String.join("|", temizlik.hedefler()) + "> [--onayla]");
                    s.sendMessage("§7Onaysız çalıştırılırsa sadece deneme (dry-run) raporu verir, hiçbir şey silmez.");
                    return true;
                }
                boolean onayla = args.length > 2 && args[2].equalsIgnoreCase("--onayla");
                temizlik.komut(s, args[1], onayla);
            }
            case "reload" -> reload.run();
            case "durum" -> durum(s);
            default -> s.sendMessage("§eKullanım: §f/optimize <rapor [dünya]|temizle <hedef> [--onayla]|durum|reload>");
        }
        return true;
    }

    private void durum(CommandSender s) {
        double[] tps = Bukkit.getServer().getTPS();
        double mspt = Bukkit.getServer().getAverageTickTime();
        s.sendMessage("§6§l=== Optimize Durum ===");
        s.sendMessage("§7TPS (1/5/15 dk): " + tpsRenk(tps[0]) + " §7/ " + tpsRenk(tps[1]) + " §7/ " + tpsRenk(tps[2])
                + " §7| MSPT ort: " + (mspt > 50 ? "§c" : mspt > 40 ? "§e" : "§a") + String.format(Locale.US, "%.1f", mspt));
        s.sendMessage("§7Yük modu: " + (!ayar.yukAktif ? "§8kapalı (config)" : yuk.acik() ? "§cAÇIK" : "§abeklemede")
                + (ayar.yukAktif ? " §8(aç < " + ayar.yukAcilisTps + ", kapa >= " + ayar.yukKapanisTps + ")" : ""));
        long[] b = ana.blockedCounts();
        s.sendMessage("§7Üretim sınırları: " + (ana.limitsEnabled() ? "§aetkin" : "§ckapalı") + " §7| engellenen spawn/üreme/yerleştirme: §f"
                + b[0] + "/" + b[1] + "/" + b[2]);
        for (KoninskiEntityLimit.Group g : ana.groups()) {
            s.sendMessage("§7 " + (g.aktif() ? "§a● " : "§8○ ") + "§f" + g.label() + " §7chunk " + g.chunkLimit() + ", 3x3 " + g.areaLimit()
                    + (g.reasons().isEmpty() ? "" : " §8[" + String.join(",", g.reasons()) + "]"));
        }
        StringBuilder bl = new StringBuilder();
        for (Map.Entry<Material, Integer> e : ayar.blokLimitleri.entrySet()) bl.append(e.getKey().name().toLowerCase(Locale.ROOT)).append(' ').append(e.getValue()).append(", ");
        s.sendMessage("§7Blok limitleri: " + (ayar.blokAktif ? "§aetkin §7" + (bl.length() > 2 ? bl.substring(0, bl.length() - 2) : "-") : "§8kapalı"));
        s.sendMessage("§7Son temizlik: §f" + temizlik.sonOzet());
    }

    private static String tpsRenk(double t) {
        return (t >= 18 ? "§a" : t >= 15 ? "§e" : "§c") + String.format(Locale.US, "%.1f", Math.min(20.0, t));
    }

    public List<String> tamamla(String[] args) {
        List<String> o = new ArrayList<>();
        if (args.length == 1) o.addAll(List.of("rapor", "temizle", "durum", "reload"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("rapor")) Bukkit.getWorlds().forEach(w -> o.add(w.getName()));
        else if (args.length == 2 && args[0].equalsIgnoreCase("temizle")) o.addAll(temizlik.hedefler());
        else if (args.length == 3 && args[0].equalsIgnoreCase("temizle")) o.add("--onayla");
        String y = args[args.length - 1].toLowerCase(Locale.ROOT);
        o.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(y));
        return o;
    }
}
