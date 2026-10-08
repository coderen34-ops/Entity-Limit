package tr.com.koninski.limit.optimize;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Optimizasyon ayarlari: config okununca tamamen onbellege alinir, olaylarda config okunmaz.
 * Gecersiz deger IllegalArgumentException verir; reload'da onceki ayarlar korunur.
 */
public final class OptimizeAyarlar {

    public enum KoyluAracIslem { RAPOR, INDIR, INDIR_VE_ARACI_SIL }

    // Tarama
    public final int taramaChunkPerTick;
    // Yerdeki esya
    public final int esyaUyariEsigi;
    public final int esyaYasDakika;
    // Blok entity limiti
    public final boolean blokAktif;
    public final boolean araziMuafiyeti;
    public final Map<Material, Integer> blokLimitleri;
    // Temizlik
    public final int oyuncuKorumaYaricapi;
    public final boolean herhangiPdcKoru;
    public final Set<String> korumaliNamespaceler;
    public final KoyluAracIslem koyluAracIslem;
    // Yuk modu
    public final boolean yukAktif;
    public final int yukAralikSaniye;
    public final double yukAcilisTps;
    public final double yukKapanisTps;
    public final boolean yukBildirim;
    public final Set<String> yukSebepler;
    public final boolean yukCanavar;
    public final boolean yukAmbient;

    private OptimizeAyarlar(FileConfiguration c) {
        taramaChunkPerTick = aralik(c, "optimize.tarama-chunk-per-tick", 20, 1, 500);

        esyaUyariEsigi = aralik(c, "yerdeki-esya.uyari-esigi", 150, 10, 100000);
        // Vanilla yerdeki esyayi 5 dakikada kendisi siler; daha uzun bir yas hic eslesmez
        esyaYasDakika = aralik(c, "yerdeki-esya.temizlik-yas-dakika", 2, 1, 1440);

        blokAktif = c.getBoolean("blok-limitleri.aktif", true);
        araziMuafiyeti = c.getBoolean("blok-limitleri.arazi-muafiyeti", false);
        Map<Material, Integer> limitler = new EnumMap<>(Material.class);
        ConfigurationSection l = c.getConfigurationSection("blok-limitleri.limitler");
        if (l != null) {
            for (String ad : l.getKeys(false)) {
                Material m = Material.matchMaterial(ad);
                if (m == null || !m.isBlock()) throw new IllegalArgumentException("blok-limitleri: bilinmeyen blok " + ad);
                int n = l.getInt(ad, -1);
                if (n < 1) throw new IllegalArgumentException("blok-limitleri." + ad + " en az 1 olmali");
                limitler.put(m, n);
            }
        }
        blokLimitleri = Map.copyOf(limitler);

        oyuncuKorumaYaricapi = aralik(c, "temizlik.oyuncu-koruma-yaricapi", 24, 0, 256);
        herhangiPdcKoru = c.getBoolean("temizlik.herhangi-pdc-koru", true);
        Set<String> ns = new HashSet<>();
        List<String> nsListe = c.isList("temizlik.korumali-pdc-namespaceler") ? c.getStringList("temizlik.korumali-pdc-namespaceler")
                : List.of("economy", "mesleksistemi", "klansistemi", "arenaligi", "tagplugin");
        for (String s : nsListe) ns.add(s.toLowerCase(Locale.ROOT));
        korumaliNamespaceler = Set.copyOf(ns);
        String islem = c.getString("temizlik.koylu-arac-islem", "RAPOR").toUpperCase(Locale.ROOT);
        try {
            koyluAracIslem = KoyluAracIslem.valueOf(islem);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("temizlik.koylu-arac-islem RAPOR, INDIR ya da INDIR_VE_ARACI_SIL olmali");
        }

        yukAktif = c.getBoolean("yuk-modu.aktif", false);
        yukAralikSaniye = aralik(c, "yuk-modu.kontrol-saniye", 10, 2, 300);
        yukAcilisTps = c.getDouble("yuk-modu.acilis-tps", 16.0);
        yukKapanisTps = c.getDouble("yuk-modu.kapanis-tps", 18.5);
        if (!(yukAcilisTps > 0 && yukKapanisTps > yukAcilisTps && yukKapanisTps <= 20.0)) {
            throw new IllegalArgumentException("yuk-modu: 0 < acilis-tps < kapanis-tps <= 20 olmali");
        }
        yukBildirim = c.getBoolean("yuk-modu.admin-bildirimi", true);
        Set<String> sebepler = new HashSet<>();
        List<String> sebepListe = c.isList("yuk-modu.iptal-sebepleri") ? c.getStringList("yuk-modu.iptal-sebepleri") : List.of("NATURAL");
        for (String s : sebepListe) sebepler.add(s.toUpperCase(Locale.ROOT));
        // Spawner ve mob farm sebepleri hicbir zaman yuk moduna takilmaz
        sebepler.removeAll(List.of("SPAWNER", "TRIAL_SPAWNER", "SPAWNER_EGG", "CUSTOM", "COMMAND", "BREEDING", "DISPENSE_EGG"));
        yukSebepler = Set.copyOf(sebepler);
        List<String> kat = c.getStringList("yuk-modu.kategoriler");
        yukCanavar = kat.isEmpty() || kat.stream().anyMatch(k -> k.equalsIgnoreCase("canavar"));
        yukAmbient = kat.isEmpty() || kat.stream().anyMatch(k -> k.equalsIgnoreCase("ambient"));
    }

    private static int aralik(FileConfiguration c, String yol, int v, int min, int max) {
        int n = c.getInt(yol, v);
        if (n < min || n > max) throw new IllegalArgumentException(yol + " " + min + "-" + max + " arasinda olmali");
        return n;
    }

    public static OptimizeAyarlar oku(FileConfiguration c) {
        return new OptimizeAyarlar(c);
    }
}
