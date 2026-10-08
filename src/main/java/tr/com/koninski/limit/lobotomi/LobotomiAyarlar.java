package tr.com.koninski.limit.lobotomi;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.configuration.file.FileConfiguration;

/** lobotomi.* ayarlari; okununca onbellege alinir. Gecersiz deger IllegalArgumentException verir. */
public final class LobotomiAyarlar {

    public final boolean aktif;
    public final int taramaAraligiTick;
    public final int taramaChunkPerTick;
    public final int oyuncuYaricapi;
    public final int uyandirmaAraligi;
    public final int uyanmaSuresi;
    public final int degerlendirmeAraligi;
    public final boolean aracIciAday;
    public final boolean sikisikAday;
    public final boolean meslegiOlmayanlar;
    public final boolean herhangiPdcKoru;
    public final Set<String> korumaliNamespaceler;

    private LobotomiAyarlar(FileConfiguration c) {
        aktif = c.getBoolean("lobotomi.aktif", true);
        taramaAraligiTick = aralik(c, "lobotomi.tarama-araligi-tick", 20, 5, 1200);
        taramaChunkPerTick = aralik(c, "lobotomi.tarama-chunk-per-tick", 20, 1, 1000);
        oyuncuYaricapi = aralik(c, "lobotomi.oyuncu-yakinlik-yaricapi", 16, 0, 128);
        uyandirmaAraligi = aralik(c, "lobotomi.uyandirma-araligi", 100, 20, 24000);
        uyanmaSuresi = aralik(c, "lobotomi.uyandirma-suresi", 3, 1, 200);
        if (uyanmaSuresi >= uyandirmaAraligi) throw new IllegalArgumentException("lobotomi.uyandirma-suresi, uyandirma-araligi'ndan kucuk olmali");
        degerlendirmeAraligi = aralik(c, "lobotomi.degerlendirme-araligi", 300, 20, 24000);
        aracIciAday = c.getBoolean("lobotomi.arac-ici-aday", true);
        sikisikAday = c.getBoolean("lobotomi.sikisik-aday", true);
        meslegiOlmayanlar = c.getBoolean("lobotomi.lobotomi-meslegi-olmayanlar", true);
        herhangiPdcKoru = c.getBoolean("lobotomi.herhangi-pdc-koru", true);
        List<String> ns = c.isList("lobotomi.korumali-pdc-namespaceler") ? c.getStringList("lobotomi.korumali-pdc-namespaceler")
                : List.of("economy", "mesleksistemi", "klansistemi", "arenaligi", "tagplugin");
        Set<String> s = new HashSet<>();
        for (String n : ns) s.add(n.toLowerCase(Locale.ROOT));
        korumaliNamespaceler = Set.copyOf(s);
    }

    private static int aralik(FileConfiguration c, String yol, int v, int min, int max) {
        int n = c.getInt(yol, v);
        if (n < min || n > max) throw new IllegalArgumentException(yol + " " + min + "-" + max + " arasinda olmali");
        return n;
    }

    public static LobotomiAyarlar oku(FileConfiguration c) {
        return new LobotomiAyarlar(c);
    }
}
